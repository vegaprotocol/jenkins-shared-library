


boolean agentSupported() {
    int exitCode = sh (label: 'Check if grafana agent is supported on this node', returnStatus: true, script: 'systemctl list-units --all | grep grafana-agent') as int
    return exitCode == 0
}

void writeEnvVars(String file, Map<String, String> envVars=[:]) {
    envVars.each {k, v -> 
        sh label: 'Remove existing env variable from grafana-agent env registry: ' + k, script: '''sudo sed -i 's/''' + k + '''=.*//' /etc/default/grafana-agent'''
        sh label: 'Set env variable to grafana-agent env registry: ' + k, script: '''echo "''' + k + '''=''' + v + '''" | sudo tee -a /etc/default/grafana-agent'''
    }
}

void configure(String configName, Map<String, String> extraEnvs=[:]) {
    if (!agentSupported()) {
        print("Grafana agent not supported")
        return
    }
    def jobInfo = jenkinsutils.getJobInfo()
    Map defaultEnvs = [
        AGENT_NAME: "${env.NODE_NAME}",
        JENKINS_JOB_NAME: jobInfo.job_name,
        JENKINS_JOB_URL: jobInfo.job_url,
        JENKINS_PR: jobInfo.pr,
        JENKINS_PR_JOB_NUMBER: jobInfo.pr_job_number,
        JENKINS_PR_REPO: jobInfo.pr_repo,
        JENKINS_STARTED_BY: jobInfo.started_by,
        JENKINS_STARTED_BY_USER: jobInfo.started_by_user,
    ]

    Map grafanaEnvs = defaultEnvs + extraEnvs

    Map<String, String> configFiles = [
        "basic": "grafana-agent-basic.yaml",
        "node-only": "grafana-agent-node-only.yaml",
        "market-sim": "grafana-agent-market-sim.yaml",
    ]


    if (!configName in configFiles) {
        error("Grafana-agent config not found for " + configName)
    }

    writeEnvVars("/etc/default/grafana-agent", grafanaEnvs)
    writeFile (
        text: libraryResource (
            resource: configFiles[configName]
        ),
        file: 'grafana-agent.yaml',
    )
    sh label: 'Remove old grafana-agent config', script: 'sudo rm /etc/grafana-agent.yaml || echo "OK: grafana-agent.yaml not found"'
    sh label: 'Copy the ' + configFiles[configName] + ' config file to /etc/grafana-agent.yaml', script: 'sudo cp ./grafana-agent.yaml /etc/grafana-agent.yaml';
}

void start() {
    if (!agentSupported()) {
        print("Grafana agent not supported")
        return
    }
    sh label: 'Start grafana-agent', script: 'sudo systemctl start grafana-agent'
}

void stop() {
    if (!agentSupported()) {
        print("Grafana agent not supported")
        return
    }
    sh label: 'Stop grafana-agent', script: 'sudo systemctl stop grafana-agent'
}
void restart() {
    if (!agentSupported()) {
        print("Grafana agent not supported")
        return
    }
    sh label: 'Restart grafana-agent', script: 'sudo systemctl restart grafana-agent'
}

void cleanup() {
    if (!agentSupported()) {
        print("Grafana agent not supported")
        return
    }
    stop()
    sh 'sudo rm /etc/grafana-agent.yaml || echo "OK: grafana-agent.yaml not found"'
}
