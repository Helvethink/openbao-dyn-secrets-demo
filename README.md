# Démonstration secrets dynamiques

Démo Quarkus + OpenBao montrant des identifiants PostgreSQL dynamiques qui expirent et tournent automatiquement.

## Ce que montre la démo

- un utilisateur DB dynamique est créé à la demande
- le TTL est visible en direct dans l'UI
- `INSERT` est refusé (moindre privilège)
- l'application ne stocke jamais de mot de passe DB
- l'ancien utilisateur est révoqué puis supprimé

## Construire l'image, le cluster KinD et déployer

D'abord, nous construisons l'image :
```shell
./mvnw -q -DskipTests package
docker build -f src/main/docker/Dockerfile.jvm . -t marmot-app:kind
```

Ensuite, nous créons le cluster KinD (en supprimant d'abord le cluster s'il reste d'une démo précédente) et nous chargeons l'image :
```shell
kind delete cluster -n marmot-secrets || true
kind create cluster --config=k8s/kind/cluster-config.yaml
kind load docker-image marmot-app:kind --name marmot-secrets
```

Enfin, nous déployons le namespace `marmot-demo` et les applications (PostgreSQL, OpenBao et l'app de démo) :
```shell
kubectl apply -f k8s/manifests/namespace.yaml
kubectl config set-context --current --namespace=marmot-demo
kubectl apply -f k8s/manifests
kubectl get po -n marmot-demo -w
```

## Accéder à l'application

Allez sur l'app : http://localhost:8080/ - ça ne fonctionne pas encore.
Allez sur OpenBao : http://localhost:18200 - le root token pour la démo est `ROOT_TOKEN` (mode dev OpenBao)

## Moteur d'authentification Kubernetes

Vérifier les logs :
```shell
kubectl -n marmot-demo logs -f deploy/marmot-app
```
--> cela doit indiquer que nous ne pouvons pas nous connecter à OpenBao pour l'instant (HTTP 403 forbidden):

<pre>
2026-05-07 12:27:01,915 ERROR [io.quarkus.vertx.http.runtime.QuarkusErrorHandler] (executor-thread-8) HTTP Request to /api/status failed,
error id: 254e21a6-7037-4f3b-a0d2-a510a86a1b91-153: VaultClientException{operationName='VAULT [AUTH (k8s)] Login',
requestPath='http://openbao.marmot-demo.svc.cluster.local:8200/v1/auth/kubernetes/login', status=403, errors=[permission denied]}
</pre>

Maintenant, il faut configurer OpenBao pour l'authentification Kubernetes, afin qu'il autorise l'app à accéder à OpenBao via notre SA.

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

<!-- Jetons un oeil à l'instance OpenBao et vérifions le rôle marmott-app-role créé dans les méthodes d'authentification. -->
<!-- nous reviendrons ensuite sur la policy, elle est utile quand nous configurons le moteur de secrets database -->

## Configurer et autoriser la connexion à la base
Nous devrions maintenant avoir des logs indiquant que nous n'avons pas encore accès au secret database:

<pre>
2026-05-07 12:44:46,212 ERROR [io.quarkus.vertx.http.runtime.QuarkusErrorHandler] (executor-thread-13) HTTP Request to /api/status failed, error id: 254e21a6-7037-4f3b-a0d2-a510a86a1b91-450: VaultClientException{operationName='[DYN-CREDS (database)] Generate for readonly', requestPath='http://openbao.marmot-demo.svc.cluster.local:8200/v1/database/creds/readonly', status=403, errors=[1 error occurred:
* permission denied
</pre>

Configurons d'abord le moteur de secrets database :

```shell
export BAO_ADDR=http://localhost:18200
export BAO_TOKEN=ROOT_TOKEN

bao secrets enable database

# Maintenant, nous voulons configurer la connexion à la base :

bao write database/config/postgresql \
plugin_name=postgresql-database-plugin \
allowed_roles="readonly" \
connection_url="postgresql://{{username}}:{{password}}@postgres.marmot-demo.svc.cluster.local:5432/demo?sslmode=disable" \
username="postgres" \
password="postgres"

# Maintenant, nous voulons configurer le plugin database pour PostgreSQL afin qu'il puisse générer dynamiquement des identifiants
bao write database/roles/readonly \
db_name=postgresql \
creation_statements="CREATE ROLE \"{{name}}\" WITH LOGIN PASSWORD '{{password}}' VALID UNTIL '{{expiration}}'; GRANT CONNECT ON DATABASE demo TO \"{{name}}\"; GRANT USAGE ON SCHEMA public TO \"{{name}}\"; GRANT SELECT ON ALL TABLES IN SCHEMA public TO \"{{name}}\"; ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT ON TABLES TO \"{{name}}\";" \
revocation_statements="SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '{{name}}'; REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM \"{{name}}\"; REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM \"{{name}}\"; REVOKE USAGE ON SCHEMA public FROM \"{{name}}\"; REVOKE CONNECT ON DATABASE demo FROM \"{{name}}\"; REASSIGN OWNED BY \"{{name}}\" TO postgres; DROP OWNED BY \"{{name}}\"; DROP ROLE IF EXISTS \"{{name}}\";" \
default_ttl="40s" \
max_ttl="40s"


cat <<"POLICY" >/tmp/marmot-app-policy.hcl
path "database/creds/readonly" {
capabilities = ["read"]
}
POLICY

bao policy write marmot-app /tmp/marmot-app-policy.hcl
```

Allons sur OpenBao et vérifions que le moteur de secrets database est activé, ainsi que la connexion et le rôle créés.
Vérifier les logs et l'app : tout fonctionne.
