import java.text.SimpleDateFormat

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
                    branch: params.SNAPSHOT_TESTING_BRANCH,
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
                    sh './dist/snapshot-testing run --duration ' + (params.TIMEOUT.toInteger()*60) + 's --environment ' + env.NET_NAME + ' --work-dir ./work-dir'
                } catch (e) {
                    failed = true
                    print('FAILURE: ' + e)
                }
            }

            stage('Process results') {
                currentBuild.result = 'SUCCESS'
                reason = "Unknown failure"
                String catchupDuration = "N/A"
                String extraLogLines = ""

                Map results = [:]
                if (fileExists('./work-dir/results.json')) {
                    results = readJSON file: './work-dir/results.json'
                }
                println(results)

                if (failed == true) {
                    currentBuild.result = 'FAILURE'
                } else {
                    try {
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
                        catchupDuration = results["catchup-duration"] ?: "N/A"
                        extraLogLines = results["visor-extra-log-lines"] ?: ""
                        
                        String snapshotsFrom = results["snapshot-min"] ?: 'UNKNOWN'
                        String snapshotsTo = results["snapshot-max"] ?: 'UNKNOWN'
                        int buildNo = currentBuild.number as Integer

                        archiveArtifactsToS3(buildNo, env.NET_NAME, './work-dir', snapshotsFrom, snapshotsTo)
                    } catch(e) {
                        print(e)
                        currentBuild.result = 'FAILURE'
                    }
                }
                
                // We have conditions (e.g: when the devnet1 network is dead) to not report it
                Boolean shouldSkipSlackMessage = (results["should-skip-failure"] as Boolean) ?: false
                println("SHOULD SKIP DEBUG")
                println(shouldSkipSlackMessage)
                if (!shouldSkipSlackMessage) {
                    sendSlackMessage(env.NET_NAME, reason, catchupDuration, extraLogLines)
                } else {
                    print("Not sending slack message. Snapshot testing binary decided to skip the results.")
                }
            }
        }

        stage('Node logs') {
            print('Logs have been moved to the artifacts.')
            print('See the following directory for logs:')
            print(env.BUILD_URL + 'artifact/work-dir/logs/')
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

void archiveArtifactsToS3(int buildNo, String envName, String dirName, String minSnapshot, String maxSnapshot) {
    // We are not interested in archiving non mainnet chain data
    if (envName != "mainnet") {
        return
    }

    // We do not need to archive all artifacts. People can replay if needed.
    if (buildNo % 4 != 0) {
        return
    }

    Date date = new Date()
    SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd_HHmmss")
    String dateString = sdf.format(date)

    String snapshotName = dateString + '-' + minSnapshot + '-' + maxSnapshot + '.tar.gz'

    sh '''tar \
        -I 'gzip -9' \
        --exclude ''' + dirName + '''/vega.sock \
        --exclude ''' + dirName + '''/bins \
        --exclude ''' + dirName + '''/vega_home/state/data-node/networkhistory/store/ipfs/blocks \
        -cvf  ''' + snapshotName + ''' \
    ./''' + dirName

     withCredentials([
        usernamePassword(
            credentialsId:  envName.toLowerCase() + '-snapshot-history-aws-key', 
            passwordVariable: 'AWS_SECRET_ACCESS_KEY', 
            usernameVariable: 'AWS_ACCESS_KEY_ID'
        ),
        string(credentialsId: envName.toLowerCase() + '-snapshot-history-bucket-name', variable: 'BUCKET_NAME')
    ]) {
        sh 'GOBIN="$(pwd)" go install github.com/opendevsecops/go-s3cp@8b07da75e3cd053a4543423a18605358e696fefe'
        sh '''AWS_REGION=us-east-1 ./go-s3cp --from ./''' + snapshotName + ''' --to s3://''' + BUCKET_NAME + '''/''' + snapshotName
    }
}


void sendSlackMessage(String vegaNetwork,  String reason, String catchupTime, String extraLogLines) {
    String slackChannel = '#snapshot-notify'
    String slackFailedChannel = '#snapshot-notify-failed'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''

    if (currentResult == 'SUCCESS') {
        msg = ":large_green_circle: Snapshot testing (${vegaNetwork}) - SUCCESS - <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Snapshot testing (${vegaNetwork}) - ABORTED - <${jobURL}|${jobName}>"
        color = '#000000'
    } else if (currentBuild == "UNSTABLE") {
        msg = ":interrobang: Snapshot testing(${vegaNetwork}) - UNSTABLE - <${jobURL}|${jobName}>"
        color = "#FFA500"
    } else {
        msg = ":red_circle: Snapshot testing (${vegaNetwork}) - FAILED - <${jobURL}|${jobName}>"
        color = 'danger'
    }

    if (catchupTime != null && catchupTime != "N/A") {
        msg += " (catch up in ${catchupTime})"
    }

    if (reason != null && reason != "") {
        msg += " (reason: ${reason})"
    }

    msg += " (${duration})"

    if (extraLogLines.length() > 0) {
        msg += "\n\nSnapshot-testing attached logs:\n"
        msg += "```"
        msg += extraLogLines
        msg += "```"
    }

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