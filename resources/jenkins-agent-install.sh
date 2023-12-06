#!/bin/bash
# detailed instructions -> https://github.com/vegaprotocol/devops-infra/issues/2023
: ${AGENT_NAME:=default}
: ${AGENT_SECRET:=default}
: ${JENKINS_URL:="jenkins.vega.rocks"}
export DEBIAN_FRONTEND=noninteractive

apt-get update
dpkg --configure -a
apt-get update
apt-get install -y \
    curl \
    openjdk-17-jdk \
    openjdk-17-jre \
    python3 \
    sudo \
    software-properties-common \
    ca-certificates \
    apt-transport-https \
    git
apt-add-repository -y ppa:ansible/ansible
apt-get update
apt-get install -y ansible
apt-get install -f
# apt-get upgrade -y

id ubuntu || adduser --disabled-password --gecos "" ubuntu
cat > /etc/sudoers.d/ubuntu-user <<EOF
ubuntu ALL=(ALL) NOPASSWD:ALL
EOF

mkdir -p /jenkins
curl -L -o /jenkins/agent.jar https://${JENKINS_URL}/jnlpJars/agent.jar

chown -R ubuntu:ubuntu /jenkins

cat > /etc/systemd/system/jenkins-agent.service <<EOF
[Unit]
Description=Jenkins Agent service
After=network.target
StartLimitIntervalSec=0

[Service]
Restart=always
RestartSec=1
User=ubuntu
ExecStart=java -jar /jenkins/agent.jar -jnlpUrl https://${JENKINS_URL}/computer/${AGENT_NAME}/jenkins-agent.jnlp -secret ${AGENT_SECRET} -workDir /jenkins

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable jenkins-agent
systemctl start jenkins-agent
