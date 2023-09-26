boolean supported() {
 //   int exitCode = (sh (returnStatus: true, script: 'systemctl list-units --all | grep grafana-agent')) as int
    int exitCode = 0
    return exitCode == 0
}

void writeEnvVars(String file, Map<String, String> envVars=[:]) {
    envVars.each {k, v -> 
        sh '''sudo sed -i 's/''' + k + '''=.*//' /etc/default/grafana-agent'''
        echo '''echo "''' + k + '''=''' + v + '''" | sudo tee -a /etc/default/grafana-agent'''
    }
}

void configure(String configName, Map<String, String> extraEnvs=[:]) {
    Map<String, String> configFiles = [
        "basic": "grafana-agent-basic.yaml"
    ]


    if (!configName in configFiles) {
        error("Grafana-agent config not found for " + configName)
    }

    writeEnvVars("/etc/default/grafana-agent", extraEnvs)
    writeFile (
        text: libraryResource (
            resource: configFiles[configName]
        ),
        file: 'grafana-agent.yaml',
    )
    sh 'sudo rm /etc/grafana-agent.yaml || echo "OK: grafana-agent.yaml not found"'
    sh 'sudo cp ./grafana-agent.yaml /etc/grafana-agent.yaml';
}

void start() {
    sh 'sudo systemctl start grafana-agent'
}

void stop() {
    sh 'sudo systemctl stop grafana-agent'
}

void cleanup() {
    stop()
    sh 'sudo rm /etc/grafana-agent.yaml || echo "OK: grafana-agent.yaml not found"'
}