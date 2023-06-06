#!/bin/bash -ex
for net in devnet1 stagnet1 stagnet2 stagnet3 testnet sandbox validators-testnet; do
    for i in {00..50}; do
        ssh-keygen -R "n$i.$net.vega.rocks" || true
        ssh-keyscan -t rsa,dsa "n$i.$net.vega.rocks" >> ~/.ssh/known_hosts || true
        ssh-keygen -R "api.n$i.$net.vega.rocks" || true
        ssh-keyscan -t rsa,dsa "api.n$i.$net.vega.rocks" >> ~/.ssh/known_hosts || true

        ssh-keygen -R "n$i.$net.vega.xyz" || break
        ssh-keyscan -t rsa,dsa "n$i.$net.vega.xyz" >> ~/.ssh/known_hosts || break
        ssh-keygen -R "api.n$i.$net.vega.xyz" || break
        ssh-keyscan -t rsa,dsa "api.n$i.$net.vega.xyz" >> ~/.ssh/known_hosts || break
    done
done
for i in {0..9}; do
    ssh-keygen -R "api$i.vega.community" || true
    ssh-keygen -R "be$i.vega.community" || true
    ssh-keygen -R "m$i.vega.community" || true

    ssh-keyscan -t rsa,dsa "api$i.vega.community" >> ~/.ssh/known_hosts || true
    ssh-keyscan -t rsa,dsa "be$i.vega.community" >> ~/.ssh/known_hosts || true
    ssh-keyscan -t rsa,dsa "m$i.vega.community" >> ~/.ssh/known_hosts || true
done
ssh-keygen -R "metabase.vega.community" || true
ssh-keyscan -t rsa,dsa "metabase.vega.community" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "api-token.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "github.com" >> ~/.ssh/known_hosts || true
export USE_GKE_GCLOUD_AUTH_PLUGIN="True"
