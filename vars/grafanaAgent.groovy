
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

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
    def prInfo = getPRInfo()
    Map defaultEnvs = [
        AGENT_NAME: "${env.NODE_NAME}",
        JOB_NAME: prInfo.job_name,
        JOB_URL: prInfo.job_url,
        PR: prInfo.pr,
        PR_JOB_NUMBER: prInfo.pr_job_number,
    ]

    Map grafanaEnvs = defaultEnvs + extraEnvs

    Map<String, String> configFiles = [
        "basic": "grafana-agent-basic.yaml",
        "node-only": "grafana-agent-node-only.yaml",
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

def getPRInfo() {
    String job_name = currentBuild.getProjectName()
    String job_url = currentBuild.getAbsoluteUrl()
    String pr = ""
    String pr_job_number = ""
    RunWrapper upBuild = null
    if (job_name.startsWith("PR-")) {
        upBuild = currentBuild
        // TODO: update job_name
        // job_name = ...
        print("currentBuild.getFullDisplayName=${currentBuild.getFullDisplayName()}")
        print("currentBuild.getFullProjectName=${currentBuild.getFullProjectName()}")
    } else {
        for (int i=0; i<currentBuild.upstreamBuilds.size(); i++) {
            // Find first build that getProjectName() starts with `PR-`
            if (currentBuild.upstreamBuilds[i].getProjectName().startsWith("PR-")) {
                upBuild = currentBuild.upstreamBuilds[i]
                break
            }
        }
    }

    if (upBuild != null) {
        pr = upBuild.getProjectName()
        pr_job_number = upBuild.getNumber()
    } else {
        // Probably leave "pr" and "pr_job_number" empty
    }

    return [
        job_name: job_name,
        job_url: job_url,
        pr: pr,
        pr_job_number: pr_job_number,
    ]
}