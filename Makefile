# WordSparrow top-level Makefile.
#
# Thin ergonomic wrapper around scripts/. Per CLAUDE.md, the goal is
# "one command after clone to have everything running" — these targets
# move us toward that for the local Kubernetes substrate.
#
# Module-specific build/test still lives in each bounded context
# (gradlew for grid/, pnpm for frontend/). This Makefile deliberately
# does not duplicate them.

SHELL := /usr/bin/env bash

.DEFAULT_GOAL := help

## Local cluster (k3d, mirrors prod k3s — see docs/local-development.md)

.PHONY: cluster-up cluster-down cluster-reset cluster-bootstrap deploy-local dev cluster-status help

cluster-up:        ## Create the local k3d cluster (idempotent)
	./scripts/local-cluster.sh up

cluster-down:      ## Delete the local k3d cluster
	./scripts/local-cluster.sh down

cluster-reset:     ## Delete and recreate the local k3d cluster
	./scripts/local-cluster.sh reset

cluster-bootstrap: ## Install ingress-nginx, cert-manager, CloudNative-PG via Helm
	./scripts/local-cluster.sh bootstrap

deploy-local:      ## Build grid-api + survey-api images, import into k3d, helm install
	./scripts/local-cluster.sh deploy

dev:               ## Start full-stack dev (API hot reload + Vite HMR). FORCE=1 kills strays on 7777/7778/5173.
	./scripts/local-cluster.sh dev $(if $(FORCE),--force)

cluster-status:    ## kubectl get nodes,pods -A against the local context
	./scripts/local-cluster.sh status

help:              ## Show this help
	@awk 'BEGIN { FS = ":.*?## "; printf "Targets:\n" } \
	  /^[a-zA-Z_-]+:.*?## / { printf "  %-20s %s\n", $$1, $$2 }' $(MAKEFILE_LIST)
