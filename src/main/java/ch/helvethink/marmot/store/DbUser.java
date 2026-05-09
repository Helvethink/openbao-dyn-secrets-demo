package ch.helvethink.marmot.store;

import io.quarkus.hibernate.reactive.panache.PanacheEntityBase;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "pg_user", schema = "pg_catalog")
public class DbUser extends PanacheEntityBase {

    @Id
    public String usename;
}
