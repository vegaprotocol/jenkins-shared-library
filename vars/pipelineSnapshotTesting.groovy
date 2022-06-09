/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map config=[:]) {

    properties([
        buildDiscarder(logRotator(daysToKeepStr: '14')),
        copyArtifactPermission('*'),
        pipelineTriggers([cron('H/12 * * * *')]),
        parameters([
            string(
                name: 'REMOTE_SERVER', defaultValue: config.remoteServer ?: 'n01.d.vega.xyz',
                description: 'From which machine to copy vega binary and genesis config'),
            string(
                name: 'TIMEOUT', defaultValue: config.timeout ?: '10',
                description: 'Number of minutes after which the node will stop'),
        ])
    ])

    echo "params=${params}"

    node('non-validator') {
        /* groovylint-disable-next-line NoDef, VariableTypeRequired */
        def sshDevnetCredentials = sshUserPrivateKey(  credentialsId: 'ssh-vega-network',
                                                     keyFileVariable: 'PSSH_KEYFILE',
                                                    usernameVariable: 'PSSH_USER')
        skipDefaultCheckout()
        cleanWs()
        def TM_VERSION
        def TRUST_HASH
        def TRUST_HEIGHT
        def RPC_SERVERS
        def PERSISTENT_PEERS

        def blockHeightStart
        def blockHeightEnd

        timestamps {
            try {
                // give extra 5 minutes for setup
                timeout(time: params.TIMEOUT.toInteger() + 5, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        // download dasel to edit toml files
                        sh script: "wget https://github.com/TomWright/dasel/releases/download/v1.24.3/dasel_linux_amd64 && mv dasel_linux_amd64 dasel && chmod +x dasel"
                    }

                    stage("Get vega core binary") {
                        withCredentials([sshDevnetCredentials]) {
                            sh script: "scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${params.REMOTE_SERVER}\":/home/vega/current/vega vega"
                        }
                    }

                     stage("Initialize configs") {
                        sh script: './vega init full --home=./vega_config --output=json &&./vega tm init full --home=./tm_config'
                    }

                    stage("Get Genesis") {
                        withCredentials([sshDevnetCredentials]) {
                            sh script: "scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${params.REMOTE_SERVER}\":/home/vega/.tendermint/config/genesis.json ./tm_config/config/genesis.json"
                        }
                    }


                    stage("Get Tendermint config") {
                        // Check TM version
                        def status_req = new URL("https://${params.REMOTE_SERVER}/tm/status").openConnection();
                        def status = new groovy.json.JsonSlurper().parseText(status_req.getInputStream().getText())
                        TM_VERSION = status.result.node_info.version
                        if(TM_VERSION.startsWith("0.34")) {
                            def net_info_req = new URL("https://${params.REMOTE_SERVER}/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            RPC_SERVERS = net_info.result.peers*.node_info.listen_addr.collect{addr -> addr.replaceAll(/26656/, "26657")}.join(",")
                            PERSISTENT_PEERS = net_info.result.peers*.node_info.collect{node -> node.id + "@" + node.listen_addr}.join(",")
                        } else {
                            def net_info_req = new URL("https://${params.REMOTE_SERVER}/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            def servers_with_id = net_info.result.peers*.url.collect{url -> url.replaceAll(/mconn.*\/(.*):.*/, "\$1")}
                            RPC_SERVERS = servers_with_id.collect{server -> server.split('@')[1] + ":26657"}.join(",")
                            PERSISTENT_PEERS = servers_with_id.collect{peer -> peer + ":26656"}.join(",")
                        }
                        

                        // Get trust block info
                        def block_req = new URL("https://${params.REMOTE_SERVER}/tm/block").openConnection();
                        def tm_block = new groovy.json.JsonSlurper().parseText(block_req.getInputStream().getText())
                        TRUST_HASH = tm_block.result.block_id.hash
                        TRUST_HEIGHT = tm_block.result.block.header.height
                        println("RPC_SERVERS=${RPC_SERVERS}")
                        println("PERSISTENT_PEERS=${PERSISTENT_PEERS}")
                        println("TRUST_HASH=${TRUST_HASH}")
                        println("TRUST_HEIGHT=${TRUST_HEIGHT}")
                    }

                    if(TM_VERSION.startsWith("0.34")) {
                        stage("Set Tendermint config") {
                            sh script: """
                                ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${TRUST_HASH}
                                ./dasel put string -f tm_config/config/config.toml statesync.trust_height ${TRUST_HEIGHT}
                                ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                ./dasel put string -f tm_config/config/config.toml p2p.persistent_peers ${PERSISTENT_PEERS}
                                ./dasel put string -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 7024
                                cat tm_config/config/config.toml
                            """
                        }
                    } else {
                        stage("Set Tendermint config") {
                            sh script: """
                                ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                ./dasel put string -f tm_config/config/config.toml statesync.trust-hash ${TRUST_HASH}
                                ./dasel put string -f tm_config/config/config.toml statesync.trust-height ${TRUST_HEIGHT}
                                ./dasel put string -f tm_config/config/config.toml statesync.rpc-servers ${RPC_SERVERS}
                                ./dasel put string -f tm_config/config/config.toml p2p.persistent-peers ${PERSISTENT_PEERS}
                                ./dasel put string -f tm_config/config/config.toml p2p.max-packet-msg-payload-size 7024
                                cat tm_config/config/config.toml
                            """
                        }
                    }

                    stage('Run') {
                        parallel([
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'Start vega node', script: """#!/bin/bash -e
                                        ./vega node --home=vega_config
                                    """
                                }
                                if ( !nice && isRemoteServerAlive(params.REMOTE_SERVER) ) {
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Tendermint': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'Start tendermint', script: """#!/bin/bash -e
                                        ./vega tm start --home=tm_config
                                    """
                                }
                                if ( !nice && isRemoteServerAlive(params.REMOTE_SERVER) ) {
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // run at 50sec, 1min50sec, 2min50sec, ... since start
                                    int runEveryMs = 60 * 1000
                                    int startAt = currentBuild.duration
                                    while (true) {
                                        // wait until next Xmin50sec
                                        int sleepForMs = runEveryMs - ((currentBuild.duration - startAt + 10 * 1000) % runEveryMs)
                                        sleep(time:sleepForMs, unit:'MILLISECONDS')

                                        String sinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                        sh label: "Get non-validator statistics (${sinceStartSec} sec)", script: """#!/bin/bash -e
                                            curl http://127.0.0.1:3003/statistics
                                        """
                                        sinceStartSec = Math.round((currentBuild.duration - startAt)/1000)
                                        sh label: "Get ${params.REMOTE_SERVER} statistics (${sinceStartSec} sec)", script: """#!/bin/bash -e
                                            curl https://${params.REMOTE_SERVER}/statistics
                                        """
                                    }
                                }
                            }
                        ])
                    }
                }
                currentBuild.result = 'SUCCESS'
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Notification') {
                    sendSlackMessage(params.REMOTE_SERVER)
                }
            }
        }
    }
}

boolean nicelyStopAfter(String timeoutMin, Closure body) {
    int startTimeMs = currentBuild.duration
    catchError(
        buildResult: null,
        stageResult: null,
    ) {
        timeout(time: timeoutMin, unit: 'MINUTES') {
            body()
        }
    }
    return timeoutMin * 60 * 1000 < (currentBuild.duration - startTimeMs)
}

boolean isRemoteServerAlive(String remoteServer) {
    try {
        def statistics_req = new URL("https://${remoteServer}/statistics").openConnection()
        statistics_req.setConnectTimeout(5000)
        statistics_req.getInputStream().getText()
        return true
    } catch (IOException e) {
        return false
    }
}

void sendSlackMessage(String remoteServer) {
    String slackChannel = '#monitoring'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''
    String networkDomain = remoteServer.substring(4)

    if (currentResult == 'SUCCESS') {
        
        msg = ":large_green_circle: Snapshot testing (${networkDomain}) - SUCCESS - <${jobURL}|${jobName}>"
        color = 'good'
    } else if (currentResult == 'ABORTED') {
        msg = ":black_circle: Snapshot testing (${networkDomain}) - ABORTED - <${jobURL}|${jobName}>"
        color = '#000000'
    } else {
        msg = ":red_circle: Snapshot testing (${networkDomain}) - FAILED - <${jobURL}|${jobName}>"
        color = 'danger'
    }

    msg += " (${duration})"

    echo "${msg}"

    // slackSend(
    //     channel: slackChannel,
    //     color: color,
    //     message: msg,
    // )
}
