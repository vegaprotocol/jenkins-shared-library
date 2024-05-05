void call(Map config=[:]) {
    Boolean failed = false

    node(params.NODE_LABEL) {
        stage('init') {
            skipDefaultCheckout()
            cleanWs()

            script {
                // initial cleanup
                vegautils.commonCleanup()
                // init global variables
                monitoringDashboardURL = jenkinsutils.getMonitoringDashboardURL([job: "snapshot-${env.NET_NAME}"])
                jenkinsAgentIP = agent.getPublicIP()
                echo "Jenkins Agent IP: ${jenkinsAgentIP}"
                echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
                // set job Title and Description
                String prefixDescription = jenkinsutils.getNicePrefixForJobDescription()
                currentBuild.displayName = "#${currentBuild.id} ${prefixDescription} [${env.NODE_NAME.take(12)}]"
                currentBuild.description = "Monitoring: ${monitoringDashboardURL}, Jenkins Agent IP: ${jenkinsAgentIP} [${env.NODE_NAME}]"
                // Setup grafana-agent
                grafanaAgent.configure("snapshot", [
                    JENKINS_JOB_NAME: "snapshot-${env.NET_NAME}",
                ])
                grafanaAgent.restart()
            }
        }

        stage('INFO') {
            // Print Info only, do not execute anythig
            echo "Jenkins Agent IP: ${jenkinsAgentIP}"
            echo "Jenkins Agent name: ${env.NODE_NAME}"
            echo "Monitoring Dahsboard: ${monitoringDashboardURL}"
            echo "Core stats: http://${jenkinsAgentIP}:3003/statistics"
            echo "GraphQL: http://${jenkinsAgentIP}:3008/graphql/"
            echo "Epoch: http://${jenkinsAgentIP}:3008/api/v2/epoch"
            echo "Data-Node stats: http://${jenkinsAgentIP}:3008/statistics"
        }

        // Give extra 5 minutes for setup
        timeout(time: params.TIMEOUT.toInteger() + 5, unit: 'MINUTES') {
            stage('Clone snapshot-testing') {
                gitClone([
                    url: 'git@github.com:vegaprotocol/snapshot-testing.git',
                    branch: 'main',
                    credentialsId: 'vega-ci-bot',
                    directory: 'snapshot-testing'
                ])
            }

            stage('build snapshot-testing binary') {
                dir('snapshot-testing') {
                    sh 'mkdir ../dist && go build -o ../dist ./...'
                }
            }

            stage('Run tests') {
                try {
                    sh './dist/snapshot-testing run --duration ' + (params.TIMEOUT.toInteger()*60) + 's --environment mainnet --work-dir ./work-dir'
                } catch (e) {
                    failed = true
                    print('FAILURE: ' + e)
                }
            }

            stage('Process results') {
                currentBuild.result = 'SUCCESS'
                reason = "Unknown failure"
                if (failed == true) {
                    currentBuild.result = 'FAILURE'
                } else {
                    try {
                        Map results = [:]
                        if (fileExists('./work-dir/results.json')) {
                            results = readJSON file: './work-dir/results.json'
                        }

                        println(results)
                        switch (results["status"] ?: 'UNKNOWN') {
                            case 'HEALTHY':
                                currentBuild.result = 'SUCCESS'
                                reason = ""
                                break
                            case 'MAYBE':
                                currentBuild.result = 'UNSTABLE'
                                reason = results["reason"] ?: "Unknown reason"
                                break
                            default:
                                currentBuild.result = 'FAILURE'
                                reason = results["reason"] ?: "Unknown reason"
                                break
                        }
                    } catch(e) {
                        print(e)
                        currentBuild.result = 'FAILURE'
                    }
                    
                    sendSlackMessage(env.NET_NAME, reason, null)
                }
            }

        }

        stage('Archive artifacts') {
            sh 'ls -als ./work-dir'
            archiveArtifacts(
                artifacts: 'work-dir/**/*',
                allowEmptyArchive: true,
                excludes: [
                    'work-dir/bins/*',
                    'work-dir/**/*.sock',
                ].join(','),
            )
        }

    }
}


void sendSlackMessage(String vegaNetwork,  String reason, String catchupTime) {
    String slackChannel = '#snapshot-notify'
    String slackFailedChannel = '#snapshot-notify-failed'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: (NEW - ignore it) Snapshot testing (${vegaNetwork}) - SUCCESS - <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: (NEW - ignore it) Snapshot testing (${vegaNetwork}) - ABORTED - <${jobURL}|${jobName}>"
        color = '#000000'
    } else if (currentBuild == "UNSTABLE") {
        msg = ":interrobang: (NEW - ignore it) Snapshot testing(${vegaNetwork}) - UNSTABLE - <${jobURL}|${jobName}>"
        color = "#FFA500"
    } else {
        msg = ":red_circle: (NEW - ignore it) Snapshot testing (${vegaNetwork}) - FAILED - <${jobURL}|${jobName}>"
        color = 'danger'
    }

    if (catchupTime != null) {
        msg += " (catch up in ${catchupTime})"
    }

    if (reason != null && reason != "") {
        msg += " (reason: ${reason})"
    }

    msg += " (${duration})"

    echo "${msg}"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )

    if (currentResult != 'SUCCESS') {
        slackSend(
            channel: slackFailedChannel,
            color: color,
            message: msg,
        )
    }
}