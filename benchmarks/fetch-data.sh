#!/bin/sh
# Downloads the real-world documents used by RealDocumentBenchmark into data/, pinned to fixed
# commits or releases, and verifies their SHA-256 checksums. The files are not committed.
set -eu
cd "$(dirname "$0")"
mkdir -p data

fetch() {
    name=$1
    url=$2
    sha=$3
    if [ ! -f "data/$name" ]; then
        curl -sSfL --retry 3 -o "data/$name" "$url"
    fi
    actual=$(shasum -a 256 "data/$name" | cut -d' ' -f1)
    if [ "$actual" != "$sha" ]; then
        echo "Checksum mismatch for $name: $actual" >&2
        rm -f "data/$name"
        exit 1
    fi
    echo "ok  $name"
}

# OpenAPI description of the GitHub REST API (MIT), 9.9 MB
fetch openapi-github.yaml \
    https://raw.githubusercontent.com/github/rest-api-description/58b1e0c00b39b9e46c24dc4a0ba6a2669c90e9c1/descriptions/api.github.com/api.github.com.yaml \
    bf9ebf6aa9cf9ea4ee73d00c3f62a3f0b3712e5ad314771736ba84ba1cdb1a9a
# cert-manager v1.21.2 release manifest: 50+ Kubernetes documents, CRDs with long descriptions (Apache-2.0), 1 MB
fetch cert-manager.yaml \
    https://github.com/cert-manager/cert-manager/releases/download/v1.21.2/cert-manager.yaml \
    e03b668ec8675214af6b0a671699d088f2601fa3878e0dbe1b41d3feafd1879f
# ingress-nginx controller-v1.15.1 deployment manifest, a typical multi-document Kubernetes file (Apache-2.0), 16 KB
fetch ingress-nginx.yaml \
    https://raw.githubusercontent.com/kubernetes/ingress-nginx/controller-v1.15.1/deploy/static/provider/cloud/deploy.yaml \
    502fddca66b09c20dd48b6d0a792a9671cd663a3a0d2a8bda5ae990d13b6c5b2
# kube-prometheus-stack Helm values: mostly comments (Apache-2.0), 214 KB
fetch helm-values.yaml \
    https://raw.githubusercontent.com/prometheus-community/helm-charts/4f40119b6f065438ede57539f666de9754aed038/charts/kube-prometheus-stack/values.yaml \
    96ed0b6a054e9d90d228a6977590bcc5f2c983acd01e491fe98df0967919a5f2
# docker-compose file of an application stack, without merge keys (CC0-1.0), 1.5 KB
fetch compose.yaml \
    https://raw.githubusercontent.com/docker/awesome-compose/30f4b7f6a6c3b0c0ecf4d4efb0de203c48d11562/react-express-mysql/compose.yaml \
    c18155224235f7124b7b6744bf13f6e28bc49ba106f14ead500847b370fe42b3
# Russian locale of Faker: Cyrillic text in long sequences (MIT), 44 KB
fetch faker-ru.yml \
    https://raw.githubusercontent.com/faker-ruby/faker/bb5d715bc6038d00d010c970011b664a12484979/lib/locales/ru.yml \
    3d118ebba9eff48b1280cd32b8dcbcab7ba0f2f162d7832b95cbf714d88db16e
