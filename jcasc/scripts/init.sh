#!/bin/bash -ex
for net in devnet1 stagnet1 stagnet2 stagnet3 testnet sandbox; do
    for i in {00..50}; do
        # Remove
        ssh-keygen -R "n$i.$net.vega.xyz" || true
        # Readd
        ssh-keyscan -t rsa,dsa "n$i.$net.vega.xyz" >> ~/.ssh/known_hosts || true
    done
done
ssh-keyscan -t rsa,dsa "mainnet-observer.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "testnet-observer.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "api-token.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "github.com" >> ~/.ssh/known_hosts || true
export USE_GKE_GCLOUD_AUTH_PLUGIN="True"
