
import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper
import hudson.model.Cause$UserIdCause

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
    def jobInfo = getJobInfo()
    Map defaultEnvs = [
        AGENT_NAME: "${env.NODE_NAME}",
        JENKINS_JOB_NAME: jobInfo.job_name,
        JENKINS_JOB_URL: jobInfo.job_url,
        JENKINS_PR: jobInfo.pr,
        JENKINS_PR_JOB_NUMBER: jobInfo.pr_job_number,
        JENKINS_STARTED_BY: jobInfo.staretd_by,
        JENKINS_STARTED_BY_USER: jobInfo.started_by_user,
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

Map<String, String> getJobInfo() {
    //
    // Find build not triggered by upstream build
    //
    RunWrapper triggerBuild = null
    allBuilds = [currentBuild] + currentBuild.upstreamBuilds
    print("allBuilds=${allBuilds}")
    for (int i=0; i<allBuilds.size(); i++) {
        // TODO: remove extra logging at some point
        print("allBuilds[${i}].getProjectName=${allBuilds[i].getProjectName()}")
        print("allBuilds[${i}].getDescription=${allBuilds[i].getDescription()}")
        print("allBuilds[${i}].getDisplayName=${allBuilds[i].getDisplayName()}")
        print("allBuilds[${i}].getBuildCauses=${allBuilds[i].getBuildCauses()}")
        print("allBuilds[${i}].getFullDisplayName=${allBuilds[i].getFullDisplayName()}")
        print("allBuilds[${i}].getFullProjectName=${allBuilds[i].getFullProjectName()}")

        if (allBuilds[i].getBuildCauses('hudson.model.Cause.UpstreamCause').isEmpty()) {
            // not triggered by upstream build
            triggerBuild = allBuilds[i]
            break
        }
    }
    if (triggerBuild == null) {
        print("SOMETHING IS WRONG - could not find trigger build")
        triggerBuild = currentBuild
    }
    //
    // Find who started the build
    //
    String started_by = ""
    String started_by_user = ""

    if (!triggerBuild.getBuildCauses('hudson.model.Cause$UserIdCause').isEmpty()) {
        started_by = "user"
        started_by_user = ((UserIdCause) triggerBuild.getBuildCauses('hudson.model.Cause$UserIdCause').get(0)).getUserName()
    }
    if (!triggerBuild.getBuildCauses('hudson.triggers.TimerTrigger$TimerTriggerCause').isEmpty()) {
        started_by = "cron"
    }
    if (!triggerBuild.getBuildCauses('jenkins.branch.BranchEventCause').isEmpty()) {
        started_by = "pr"
    }
    //
    // Get PR info if started by PR
    //
    String pr = ""
    String pr_job_number = ""
    if (triggerBuild.getProjectName().startsWith("PR-")) {
        pr = triggerBuild.getProjectName()
        pr_job_number = triggerBuild.getNumber()
    }
    //
    // Get Job name
    //
    String job_name = currentBuild.getProjectName()
    String job_url = currentBuild.getAbsoluteUrl()
    if (job_name.startsWith("PR-")) {
        job_name = currentBuild.getFullProjectName().split("/")[-2]
    }
    return [
        build: triggerBuild,
        job_name: job_name,
        job_url: job_url,
        pr: pr,
        pr_job_number: pr_job_number,
        started_by: started_by,
        started_by_user: started_by_user,
    ]
}