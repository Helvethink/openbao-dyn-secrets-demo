package ch.helvethink.marmot.api;

import ch.helvethink.marmot.service.CredentialRotationTracker;
import ch.helvethink.marmot.service.CredentialRotationTracker.Snapshot;
import ch.helvethink.marmot.store.Customer;
import ch.helvethink.marmot.store.DbUser;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import io.vertx.pgclient.PgException;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * REST resource for testing SQL operations.
 * Use Panache for database operations directly.
 */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MarmotSqlResource {

    /**
     * Tracks the current user and their credentials.
     */
    @Inject
    CredentialRotationTracker tracker;

    /**
     * How long a credential is valid for before being rotated.
     */
    @ConfigProperty(name = "demo.credentials.ttl", defaultValue = "2m")
    Duration credentialTtl;

    /**
     * Fetch the current user.
     * @return
     */
    @GET
    @Path("/user")
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> currentUser() {
        return fetchCurrentUser()
                .invoke(dbUser -> tracker.touch(dbUser, credentialTtl));
    }

    /**
     * Fetch all existing customers entries from DB.
     * @return
     */
    @GET
    @Path("/customers")
    public Uni<List<Map<String, Object>>> customers() {
        return Customer.<Customer>findAll(Sort.by("id"))
                .list()
                .map(rows -> rows.stream()
                        .map(row -> Map.<String, Object>of("id", row.id, "name", row.name))
                        .toList());
    }

    /**
     * Write a test customer entry to DB. Should fail into demo because the user has RO role
     * @return
     */
    @POST
    @Path("/write-test")
    @WithTransaction
    @Produces(MediaType.TEXT_PLAIN)
    public Uni<String> testWrite() {
        Customer customer = new Customer();
        customer.name = "test";
        return customer.persistAndFlush()
                .replaceWith("OK")
                .onFailure()
                .transform(this::mapDBThrowableToException);
    }

    @GET
    @Path("/status")
    public Uni<DemoStatus> status() {
        return fetchCurrentUser().chain(dbUser -> {
            Snapshot snapshot = tracker.touch(dbUser, credentialTtl);
            return listDbUsers().map(dbUsers -> new DemoStatus(
                    true,
                    snapshot.currentUser(),
                    snapshot.ttl().toSeconds(),
                    snapshot.remaining().toSeconds(),
                    snapshot.elapsed().toSeconds(),
                    "readonly",
                    "SELECT only",
                    dbUsers));
        });
    }

    /**
     * Retrieve the current DB user.
     * @return
     */
    private Uni<String> fetchCurrentUser() {
        // Native SQL only here, because CURRENT_USER is a DB/session primitive.
        return Panache.getSession()
                .chain(session -> session.createNativeQuery("SELECT current_user", String.class).getSingleResult());
    }

    /**
     * Retrieve all existing DB usernames.
     * @return
     */
    @GET
    @Path("/db-users")
    public Uni<List<String>> listDbUsers() {
        return DbUser.<DbUser>findAll(Sort.by("usename"))
                .list()
                .map(rows -> rows.stream().map(row -> row.usename).toList());
    }

    /**
     * Map a DB exception to a WebApplicationException.
     * Helps us to get a clear message with the frontend for the demo.
     * @param throwable
     * @return
     */
    private @NonNull WebApplicationException mapDBThrowableToException(Throwable throwable) {
        Throwable root = rootCause(throwable);
        String message = root.getMessage();
        if (root instanceof PgException pgException && pgException.getErrorMessage() != null) {
            message = pgException.getErrorMessage();
        }
        return new WebApplicationException(
                Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                        .type(MediaType.TEXT_PLAIN_TYPE)
                        .entity(message)
                        .build());
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * Demo status.
     * @param connected - is the database connection active?
     * @param currentUser - the current user
     * @param ttlSeconds - the credential TTL in seconds
     * @param remainingSeconds - the remaining seconds until credential rotation
     * @param elapsedSeconds - the elapsed seconds since credential rotation
     * @param role - the current user's role
     * @param permissions - the current user's permissions
     * @param dbUsers - the list of existing DB usernames
     */
    public record DemoStatus(
            boolean connected,
            String currentUser,
            long ttlSeconds,
            long remainingSeconds,
            long elapsedSeconds,
            String role,
            String permissions,
            List<String> dbUsers) {
    }
}
