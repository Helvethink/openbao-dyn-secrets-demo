# Dynamic secrets demo

Quarkus + OpenBao demo showing dynamic PostgreSQL credentials that expire and rotate automatically.

## What the demo shows

- a dynamic DB user is created on demand
- the TTL is visible live in the UI
- `INSERT` is denied (least privilege)
- the application never stores a DB password
- the old user is revoked and then deleted

## Build the image, the KinD cluster and deploy 

First we build the image:
```shell
./mvnw -q -DskipTests package
docker build -f src/main/docker/Dockerfile.jvm . -t marmot-app:kind
```

Then we create the KinD cluster (deleting the cluster first if it remains from a previous demo) and load the image:
```shell
kind delete cluster -n marmot-secrets || true
kind create cluster --config=k8s/kind/cluster-config.yaml
kind load docker-image marmot-app:kind --name marmot-secrets
```

Finally, we deploy the marmot-demo namespace and applications (PostgreSQL, OpenBao and the demo app):
```shell
kubectl apply -f k8s/manifests/namespace.yaml
kubectl config set-context --current --namespace=marmot-demo
kubectl apply -f k8s/manifests
kubectl get po -n marmot-demo -w
```

## Access the app

Go to the app: http://localhost:8080/ - it does not work yet.
Go to openbao: http://localhost:18200 - root token for demo is ROOT_TOKEN (OpenBao dev mode)

## Kubernetes authentication engine

Check logs:
```shell
kubectl -n marmot-demo logs -f deploy/marmot-app
```
--> should return that we can't connect to OpenBao right now (HTTP 403 forbidden)

<pre>
2026-05-07 12:27:01,915 ERROR [io.quarkus.vertx.http.runtime.QuarkusErrorHandler] (executor-thread-8) HTTP Request to /api/status failed, 
error id: 254e21a6-7037-4f3b-a0d2-a510a86a1b91-153: VaultClientException{operationName='VAULT [AUTH (k8s)] Login', 
requestPath='http://openbao.marmot-demo.svc.cluster.local:8200/v1/auth/kubernetes/login', status=403, errors=[permission denied]}
</pre>

Now we need to configure OpenBao for the Kubernetes authentication, so it allows the app to access openbao through our SA.

```shell
export BAO_ADDR=http://localhost:18200
export BAO_TOKEN=ROOT_TOKEN

TOKEN_REVIEWER_JWT=$(kubectl -n marmot-demo exec deploy/openbao -- sh -ec 'cat /var/run/secrets/kubernetes.io/serviceaccount/token')
KUBE_CA_CERT=$(kubectl -n marmot-demo exec deploy/openbao -- sh -ec 'cat /var/run/secrets/kubernetes.io/serviceaccount/ca.crt')

bao auth enable kubernetes

bao write auth/kubernetes/config \
token_reviewer_jwt="$TOKEN_REVIEWER_JWT" \
kubernetes_host="https://kubernetes.default.svc:443" \
kubernetes_ca_cert="$KUBE_CA_CERT"

bao write auth/kubernetes/role/marmot-app-role \
bound_service_account_names="marmot-app-sa" \
bound_service_account_namespaces="marmot-demo" \
policies="marmot-app" \
ttl="1h"
```

<!-- Let's have a look at the openbao instance, and check the created marmott-app-role into Authentication methods. -->
<!-- we'll come back to the policy afterwards, it's useful when we setup the database secret engine -->

## Setup and authorize database login
We should now have some logs telling us that we can't access to the database secret for now

<pre>
2026-05-07 12:44:46,212 ERROR [io.quarkus.vertx.http.runtime.QuarkusErrorHandler] (executor-thread-13) HTTP Request to /api/status failed, error id: 254e21a6-7037-4f3b-a0d2-a510a86a1b91-450: VaultClientException{operationName='[DYN-CREDS (database)] Generate for readonly', requestPath='http://openbao.marmot-demo.svc.cluster.local:8200/v1/database/creds/readonly', status=403, errors=[1 error occurred:
* permission denied
</pre>

Let's setup the database secret engine first:

```shell
export BAO_ADDR=http://localhost:18200
export BAO_TOKEN=ROOT_TOKEN

bao secrets enable database 

# Now we want to configure the connection to the database:

bao write database/config/postgresql \
plugin_name=postgresql-database-plugin \
allowed_roles="readonly" \
connection_url="postgresql://{{username}}:{{password}}@postgres.marmot-demo.svc.cluster.local:5432/demo?sslmode=disable" \
username="postgres" \
password="postgres"

# Now we want to configure the database plugin for postgresql so it can dynamically generate credentials
bao write database/roles/readonly \
db_name=postgresql \
creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE demo TO \"{{name}}\"; GRANT USAGE ON SCHEMA public TO \"{{name}}\"; GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO \"{{name}}\";" \
revocation_statements="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '{{name}}'; REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM \"{{name}}\"; REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM \"{{name}}\"; REVOKE USAGE ON SCHEMA public FROM \"{{name}}\"; REVOKE CONNECT ON DATABASE demo FROM \"{{name}}\"; REASSIGN OWNED BY \"{{name}}\" TO postgres; DROP OWNED BY \"{{name}}\"; DROP ROLE IF EXISTS \"{{name}}\";" \
default_ttl="1m" \
max_ttl="1m"


cat <<"POLICY" >/tmp/marmot-app-policy.hcl
path "database/creds/readonly" {
capabilities = ["read"]
}
POLICY

bao policy write marmot-app /tmp/marmot-app-policy.hcl
```

Let's go to openbao and check the database secret engine is enabled, the connection and the role we created.
Check logs and app : everything's fine.