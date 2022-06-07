/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(REMOTE_SERVER="n01.d.vega.xyz") {
    node {
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
                timeout(time: 8, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        // download dasel to edit toml files
                        sh script: "wget https://github.com/TomWright/dasel/releases/download/v1.24.3/dasel_linux_amd64 && mv dasel_linux_amd64 dasel && chmod +x dasel"
                    }

                    stage("Get vega core binary") {
                        withCredentials([sshDevnetCredentials]) {
                            sh script: "scp -i ${PSSH_KEYFILE} ${PSSH_USER}@${REMOTE_SERVER}:/home/vega/current/vega vega"
                        }
                    }

                     stage("Initialize configs") {
                        sh script: './vega init full --home=./vega_config --output=json &&./vega tm init full --home=./tm_config'
                    }

                    stage("Get Genesis") {
                        withCredentials([sshDevnetCredentials]) {
                            sh script: "scp -i ${PSSH_KEYFILE} ${PSSH_USER}@${REMOTE_SERVER}:/home/vega/.tendermint/config/genesis.json ./tm_config/config/genesis.json"
                        }
                    }


                    stage("Get Tendermint config") {
                        // Check TM version
                        def status_req = new URL("https://" + REMOTE_SERVER + "/tm/status").openConnection();
                        def status = new groovy.json.JsonSlurper().parseText(status_req.getInputStream().getText())
                        TM_VERSION = status.result.node_info.version
                        if(TM_VERSION.startsWith("0.34")) {
                            def net_info_req = new URL("https://" + REMOTE_SERVER + "/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            RPC_SERVERS = net_info.result.peers*.node_info.listen_addr.collect{addr -> addr.replaceAll(/26656/, "26657")}.join(",")
                            PERSISTENT_PEERS = net_info.result.peers*.node_info.collect{node -> node.id + "@" + node.listen_addr}.join(",")
                        } else {
                            def net_info_req = new URL("https://" + REMOTE_SERVER + "/tm/net_info").openConnection();
                            def net_info = new groovy.json.JsonSlurper().parseText(net_info_req.getInputStream().getText())
                            def servers_with_id = net_info.result.peers*.url.collect{url -> url.replaceAll(/mconn.*\/(.*):.*/, "\$1")}
                            RPC_SERVERS = servers_with_id.collect{server -> server.split('@')[1] + ":26657"}.join(",")
                            PERSISTENT_PEERS = servers_with_id.collect{peer -> peer + ":26656"}.join(",")
                        }
                        

                        // Get trust block info
                        def block_req = new URL("https://" + REMOTE_SERVER + "/tm/block").openConnection();
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
                                sh script: './vega node --home=vega_config'
                            },
                            'Tendermint': {
                                sh script: './vega tm start --home=tm_config'
                            },
                            'Checks': {
                                sh script: 'sleep 60'
                                blockHeightStart = sh(script: 'curl http://127.0.0.1:26657/status|jq -r .result.sync_info.latest_block_height', returnStdout: true)
                                println("Block height after 1 minute: " + blockHeightStart)
                                sh script: 'sleep 360'
                                blockHeightEnd = sh(script: 'curl http://127.0.0.1:26657/status|jq -r .result.sync_info.latest_block_height', returnStdout: true)
                                println("Block height after 7 minute: " + blockHeightEnd)
                            }
                        ])
                    }

                }

                if(currentBuild.duration >= 290000) { // longer than 5min
                    currentBuild.result = 'SUCCESS'
                } else {
                    currentBuild.result = 'FAILURE'
                }
                
            } catch (FlowInterruptedException e) {
                currentBuild.result = 'ABORTED'
                throw e
            } catch (e) {
                currentBuild.result = 'FAILURE'
                throw e
            } finally {
                stage('Notification') {
                    sendSlackMessage()
                }
            }
        }
    }
}

void sendSlackMessage() {
    String slackChannel = '#monitoring'
    String jobURL = env.RUN_DISPLAY_URL
    String jobName = currentBuild.displayName

    String currentResult = currentBuild.result ?: currentBuild.currentResult
    String duration = currentBuild.durationString - ' and counting'
    String msg = ''
    String color = ''
    String networkDomain = REMOTE_SERVER.substring(4)

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

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
