# Variables for Docker image configuration
IMAGE_NAME := bus-stop-locator
PROD_REGISTRY := [REDACTED_PROD]
SANDBOX_REGISTRY := [REDACTED_SANDBOX]
GIT_COMMIT := $(shell git rev-parse --short HEAD)
TAG := $(GIT_COMMIT)

# Default target
.PHONY: all
all: build

.PHONY: build
build:
	@echo "Building Docker image with tag: $(TAG)"
	docker build --platform linux/amd64 -t $(IMAGE_NAME):$(TAG) -t $(PROD_REGISTRY)/$(IMAGE_NAME):$(TAG) -t $(SANDBOX_REGISTRY)/$(IMAGE_NAME):$(TAG) -t $(IMAGE_NAME):latest .

.PHONY: push-prod
push-prod:
	@echo "Pushing Docker image to $(PROD_REGISTRY)"
	docker push $(PROD_REGISTRY)/$(IMAGE_NAME):$(TAG)

.PHONY: push-sandbox
push-sandbox:
	@echo "Pushing Docker image to $(SANDBOX_REGISTRY)"
	docker push $(SANDBOX_REGISTRY)/$(IMAGE_NAME):$(TAG)