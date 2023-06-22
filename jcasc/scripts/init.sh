#!/bin/bash -ex
echo 'export USE_GKE_GCLOUD_AUTH_PLUGIN="True"' >> $HOME/.bashrc
cat > $HOME/.ssh/config <<EOF
Host *
    StrictHostKeyChecking no
EOF
