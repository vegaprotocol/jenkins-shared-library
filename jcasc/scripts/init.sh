#!/bin/bash -ex
cat > ~/.ssh/config <<EOF
    Host n01-d
        HostName n01.d.vega.xyz
    Host n02-d
        HostName n02.d.vega.xyz
    Host n03-d
        HostName n03.d.vega.xyz
    Host n04-d
        HostName n04.d.vega.xyz
    Host n01-s
        HostName n01.s.vega.xyz
    Host n02-s
        HostName n02.s.vega.xyz
    Host n03-s
        HostName n03.s.vega.xyz
    Host n04-s
        HostName n04.s.vega.xyz
    Host n05-s
        HostName n05.s.vega.xyz
    Host n01-testnet
        HostName n01.testnet.vega.xyz
    Host n02-testnet
        HostName n02.testnet.vega.xyz
    Host n03-testnet
        HostName n03.testnet.vega.xyz
    Host n04-testnet
        HostName n04.testnet.vega.xyz
    Host n05-testnet
        HostName n05.testnet.vega.xyz
    Host n06-testnet
        HostName n06.testnet.vega.xyz
    Host n07-testnet
        HostName n07.testnet.vega.xyz
    Host n08-testnet
        HostName n08.testnet.vega.xyz
    Host n09-testnet
        HostName n09.testnet.vega.xyz
    Host n10-testnet
        HostName n10.testnet.vega.xyz
    Host n11-testnet
        HostName n11.testnet.vega.xyz
    Host n12-testnet
        HostName n12.testnet.vega.xyz
    Host vega-bot-1
        HostName bots.ops.vega.xyz
EOF
chmod 600 ~/.ssh/config
# Update known_hosts for Devnet
for i in {1..4}; do
    # Remove
    ssh-keygen -R "n0$i.d.vega.xyz" || true
    # Readd
    ssh-keyscan -t rsa,dsa "n0$i.d.vega.xyz" >> ~/.ssh/known_hosts || true
done
# Update known_hosts for Stagnet
for i in {1..5}; do
    # Remove
    ssh-keygen -R "n0$i.s.vega.xyz" || true
    # Readd
    ssh-keyscan -t rsa,dsa "n0$i.s.vega.xyz" >> ~/.ssh/known_hosts || true
done
# Update known_hosts for Stagnet 2
for i in {1..5}; do
    # Remove
    ssh-keygen -R "n0$i.stagnet2.vega.xyz" || true
    # Readd
    ssh-keyscan -t rsa,dsa "n0$i.stagnet2.vega.xyz" >> ~/.ssh/known_hosts || true
    # Remove
    ssh-keygen -R "v0$i.stagnet2.vega.xyz" || true
    # Readd
    ssh-keyscan -t rsa,dsa "v0$i.stagnet2.vega.xyz" >> ~/.ssh/known_hosts || true
done
# Update Fairground
for i in {01..12}; do
    # Remove
    ssh-keygen -R "n$i.testnet.vega.xyz" || true
    # Readd
    ssh-keyscan -t rsa,dsa "n$i.testnet.vega.xyz" >> ~/.ssh/known_hosts || true
done
# Update known_hosts for vega-bot-1
ssh-keyscan -t rsa,dsa "bots.ops.vega.xyz" >> ~/.ssh/known_hosts || true
# Update known_hosts for observers
ssh-keyscan -t rsa,dsa "mainnet-observer.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "testnet-observer.ops.vega.xyz" >> ~/.ssh/known_hosts || true
ssh-keyscan -t rsa,dsa "api-token.ops.vega.xyz" >> ~/.ssh/known_hosts || true
