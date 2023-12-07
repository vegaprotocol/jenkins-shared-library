#!/bin/bash
set -x

: ${AGENT_NAME:=default}
: ${AGENT_SECRET:=default}
: ${JENKINS_URL:="jenkins.vega.rocks"}
export DEBIAN_FRONTEND=noninteractive

apt-get update;
dpkg --configure -a;
apt-get update;
apt-get install -y \
    curl \
    openjdk-17-jdk \
    openjdk-17-jre \
    python3 \
    sudo \
    software-properties-common \
    ca-certificates \
    apt-transport-https \
    git;
apt-add-repository -y ppa:ansible/ansible;
apt-get update;
apt-get install -y ansible;
apt-get install -f;
# apt-get upgrade -y

id ubuntu || adduser --disabled-password --gecos "" ubuntu ;

cat > /etc/sudoers.d/ubuntu-user <<EOF
ubuntu ALL=(ALL) NOPASSWD:ALL
EOF

mkdir -p /jenkins

chown -R ubuntu:ubuntu /jenkins

cat > /etc/systemd/system/jenkins-agent.service <<EOF
[Unit]
Description=Jenkins Agent service
StartLimitIntervalSec=0
After=network-online.target
Wants=network-online.target


[Service]
Type=simple
Restart=always
RestartSec=1
User=ubuntu
ExecStartPre=mkdir -p /jenkins
ExecStartPre=rm -f /jenkins/agent.jar || echo 'OK'
ExecStartPre=curl -L -o /jenkins/agent.jar https://${JENKINS_URL}/jnlpJars/agent.jar
ExecStart=java -Xms1g -Xmx2g  -XX:+UseG1GC -XX:+ExplicitGCInvokesConcurrent -XX:+ParallelRefProcEnabled -XX:+UseStringDeduplication -XX:+UnlockExperimentalVMOptions -XX:G1NewSizePercent=20 -XX:+UnlockDiagnosticVMOptions -XX:G1SummarizeRSetStatsPeriod=1 -Djenkins.websocket.pingInterval=10 -jar /jenkins/agent.jar -jnlpUrl https://${JENKINS_URL}/computer/${AGENT_NAME}/jenkins-agent.jnlp -secret ${AGENT_SECRET} -workDir /jenkins

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable jenkins-agent
systemctl start jenkins-agent
