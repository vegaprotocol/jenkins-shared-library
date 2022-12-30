/* groovylint-disable DuplicateStringLiteral */
/* groovylint-disable DuplicateNumberLiteral */
/* groovylint-disable MethodSize */
/* groovylint-disable NestedBlockDepth */
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

void call(Map config=[:]) {
    echo "params=${params}"

    /* groovylint-disable-next-line NoDef, VariableTypeRequired */
    def sshDevnetCredentials = sshUserPrivateKey(
        credentialsId: 'ssh-vega-network',
        keyFileVariable: 'PSSH_KEYFILE',
        usernameVariable: 'PSSH_USER'
    )

    String remoteServer
    String jenkinsAgentPublicIP

    // api output placeholders
    def TM_VERSION
    def TRUST_HASH
    def TRUST_HEIGHT
    def RPC_SERVERS
    def SEEDS
    def SNAPSHOT_HEIGHT
    def SNAPSHOT_HASH
    def PEERS

    // Checks
    String extraMsg = null  // extra message to print on Slack. In case of multiple message, keep only first.
    String catchupTime = null
    boolean chainStatusConnected = false
    boolean caughtUp = false
    boolean blockHeightIncreased = false


    node('non-validator') {
        skipDefaultCheckout()
        cleanWs()
        sh 'docker kill $(docker ps -q)'

        timestamps {
            try {
                // give extra 5 minutes for setup
                timeout(time: params.TIMEOUT.toInteger() + 5, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        jenkinsAgentPublicIP = sh(
                            script: 'curl --max-time 3 http://169.254.169.254/latest/meta-data/public-ipv4',
                            returnStdout: true,
                        ).trim()
                        echo "jenkinsAgentPublicIP=${jenkinsAgentPublicIP}"
                    }

                    stage('Find available remote server') {
                        gitClone(
                            url: 'git@github.com:vegaprotocol/ansible.git',
                            branch: 'master',
                            directory: 'ansible',
                            credentialsId: 'vega-ci-bot',
                            timeout: 2,
                        )
                        def networkServers = readYaml(
                            file: "ansible/inventories/${env.NET_NAME}.yaml"
                        )[env.NET_NAME]['hosts']
                            .findAll{serverName, serverSettings -> serverSettings.get('data_node', false)}
                            .collect{serverName, serverSettings -> serverName}

                        Collections.shuffle(networkServers as List)

                        echo "Going to check servers: ${networkServers}"
                        remoteServer = networkServers.find{ serverName -> isRemoteServerAlive(serverName) }
                        if ( remoteServer == null ) {
                            // No single machine online means that Vega Network is down
                            // This is quite often for Devnet, when deployments happen all the time
                            extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                            currentBuild.result = 'ABORTED'
                            error("${env.NET_NAME} seems down")
                        }
                        echo "Found available server: ${remoteServer}"
                    }

                    stage('Download') {
                        parallel([
                            // TODO: add to jenkins-agnet build
                            'dasel & data node config': {
                                sh label: 'download dasel to edit toml files',
                                    script: '''#!/bin/bash -e
                                        wget https://github.com/TomWright/dasel/releases/download/v1.24.3/dasel_linux_amd64 && \
                                            mv dasel_linux_amd64 dasel && \
                                            chmod +x dasel
                                    '''
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp data node config from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/vega_home/config/data-node/config.toml data-node-config.toml
                                        """
                                }
                                PEERS = sh(
                                    label: 'read persistent peers',
                                    script: './dasel -f data-node-config.toml -w json -c DeHistory.Store.BootstrapPeers',
                                    returnStdout: true
                                ).trim()
                                echo "PEERS=${PEERS}"
                            },
                            'vega core binary': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp vega core from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/vegavisor_home/current/vega vega
                                        """
                                    sh label: "vega version", script: """#!/bin/bash -e
                                        ./vega version
                                    """
                                }
                            },
                            'genesis.json': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp genesis.json from ${remoteServer}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServer}\":/home/vega/tendermint_home/config/genesis.json genesis.json
                                        """
                                    sh label: "print genesis.json", script: """#!/bin/bash -e
                                        cat genesis.json
                                    """
                                }
                            }
                        ])
                    }

                    stage("Initialize configs") {
                        sh label: 'vega init + copy genesis.json',
                            script: '''#!/bin/bash -e
                                ./vega init full --home=./vega_config --output=json
                                ./vega tm init full --home=./tm_config
                                ./vega datanode init --home=./vega_config $(./dasel -f genesis.json -r json chain_id | sed 's|"||g')
                                cp genesis.json ./tm_config/config/genesis.json
                            '''
                    }

                    stage("Get API data related to configs") {
                        try {
                            // Check last snapshoted block
                            def snapshot_req = new URL("https://api.${remoteServer}/api/v2/snapshots").openConnection()
                            def snapshot = new groovy.json.JsonSlurperClassic().parseText(snapshot_req.getInputStream().getText())
                            def snapshotInfo = snapshot['coreSnapshots']['edges'][0]['node']
                            SNAPSHOT_HEIGHT = snapshotInfo['blockHeight']
                            SNAPSHOT_HASH = snapshotInfo['blockHash']
                            println("SNAPSHOT_HEIGHT=${SNAPSHOT_HEIGHT}")
                            println("SNAPSHOT_HASH=${SNAPSHOT_HASH}")

                            // Check TM version
                            def status_req = new URL("https://tm.${remoteServer}/status").openConnection()
                            def status = new groovy.json.JsonSlurperClassic().parseText(status_req.getInputStream().getText())
                            TM_VERSION = status.result.node_info.version
                            println("TM_VERSION=${TM_VERSION}")

                            // Get data from TM
                            def net_info_req = new URL("https://tm.${remoteServer}/net_info").openConnection()
                            def net_info = new groovy.json.JsonSlurperClassic().parseText(net_info_req.getInputStream().getText())
                            RPC_SERVERS = net_info.result.peers*.node_info.listen_addr.take(2).collect{addr -> addr.replaceAll(/26656/, "26657")}.join(",")
                            SEEDS = net_info.result.peers*.node_info.findAll{node -> !node.listen_addr.contains("/")}.collect{node -> node.id + "@" + node.listen_addr}.join(",")
                            println("RPC_SERVERS=${RPC_SERVERS}")
                            println("SEEDS=${SEEDS}")

                            // Get trust block info
                            def block_req = new URL("https://tm.${remoteServer}/block?height=2").openConnection()
                            def tm_block = new groovy.json.JsonSlurperClassic().parseText(block_req.getInputStream().getText())
                            TRUST_HASH = tm_block.result.block_id.hash
                            TRUST_HEIGHT = tm_block.result.block.header.height
                            println("TRUST_HASH=${TRUST_HASH}")
                            println("TRUST_HEIGHT=${TRUST_HEIGHT}")
                        } catch (e) {
                            if ( !isRemoteServerAlive(remoteServer) ) {
                                // Remote server stopped being available.
                                // This is quite often for Devnet, when deployments happen all the time
                                extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                                currentBuild.result = 'ABORTED'
                                error("${env.NET_NAME} seems down")
                            } else {
                                println("Remote server ${remoteServer} is still up.")
                                // re-throw
                                throw e
                            }
                        }
                    }
                    stage("Set configs") {
                        parallel(
                            failFast: false,
                            'tendermint': {
                                sh label: 'set Tendermint config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f tm_config/config/config.toml statesync.enable true
                                        ./dasel put string -f tm_config/config/config.toml statesync.trust_period "744h0m0s"
                                        ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${TRUST_HASH}
                                        ./dasel put int -f tm_config/config/config.toml statesync.trust_height ${TRUST_HEIGHT}
                                        ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                        ./dasel put string -f tm_config/config/config.toml statesync.discovery_time "30s"
                                        ./dasel put string -f tm_config/config/config.toml statesync.chunk_request_timeout "30s"
                                        ./dasel put string -f tm_config/config/config.toml p2p.seeds ${SEEDS}
                                        ./dasel put int -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 16384
                                        ./dasel put string -f tm_config/config/config.toml p2p.external_address "${jenkinsAgentPublicIP}:26656"
                                        ./dasel put bool -f tm_config/config/config.toml p2p.allow_duplicate_ip true
                                        cat tm_config/config/config.toml
                                    """
                            },
                            'vega': {
                                sh label: 'set vega config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f vega_config/config/node/config.toml Broker.Socket.Enabled true
                                        cat vega_config/config/node/config.toml
                                    """
                            },
                            'data-node': {
                                sh label: 'set data-node config',
                                    script: """#!/bin/bash -e
                                        ./dasel put bool -f vega_config/config/data-node/config.toml AutoInitialiseFromDeHistory true
                                        ./dasel put bool -f vega_config/config/data-node/config.toml SQLStore.UseEmbedded false
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Host 127.0.0.1
                                        ./dasel put int -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Port 5432
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Username vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Password vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Database vega
                                        sed -i 's|.*BootstrapPeers.*|    BootstrapPeers = ${PEERS}|g' vega_config/config/data-node/config.toml
                                        cat vega_config/config/data-node/config.toml
                                    """
                                    // ^ easier to use sed rather than dasel. number of spaces is hardcoded and PEERS var is in toml compatible format (minimized JSON)
                            }
                        )
                    }

                    stage('Run') {
                        parallel(
                            failFast: true,
                            'Postgres': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    sh label: 'run postgres',
                                        script: '''#!/bin/bash -e
                                            docker run \
                                                -e POSTGRES_PASSWORD=vega \
                                                -e POSTGRES_DB=vega \
                                                -e POSTGRES_USER=vega \
                                                -v /jenkins/workspace:/jenkins/workspace \
                                                -u $UID:$GID \
                                                -p 5432:5432 \
                                                    timescale/timescaledb:latest-pg14
                                        '''
                                }
                            },
                            'Data node': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // wait for db
                                    sleep(time: '20', unit:'SECONDS')
                                    sh label: 'run data node',
                                        script: """#!/bin/bash -e
                                            ./vega datanode start --home=vega_config
                                        """
                                }
                            },
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sleep(time: '25', unit:'SECONDS')
                                    sh label: 'Start vega node',
                                        script: """#!/bin/bash -e
                                            ./vega start --home=vega_config \
                                                --tendermint-home=tm_config \
                                                --snapshot.log-level=debug \
                                                --snapshot.load-from-block-height=${SNAPSHOT_HEIGHT}
                                        """
                                }
                                archiveArtifacts(
                                    artifacts: 'tm_config/**/*',
                                    allowEmptyArchive: true
                                )
                                archiveArtifacts(
                                    artifacts: 'vega_config/**/*',
                                    allowEmptyArchive: true
                                )
                                if ( !nice && isRemoteServerAlive(remoteServer) ) {
                                    extraMsg = extraMsg ?: "Vega core stopped too early."
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    sleep(time: '30', unit:'SECONDS')
                                    // run at 20sec, 50sec, 1min20sec, 1min50sec, 2min20sec, ... since start
                                    int runEverySec = 30
                                    int runEveryMs = runEverySec * 1000
                                    int startAt = currentBuild.duration
                                    int previousLocalHeight = -1
                                    String currTime = currentBuild.durationString - ' and counting'
                                    println("Checks are run every ${runEverySec} seconds (${currTime})")
                                    while (true) {
                                        // wait until next 20 or 50 sec past full minute since start
                                        int sleepForMs = runEveryMs - ((currentBuild.duration - startAt + 10 * 1000) % runEveryMs)
                                        sleep(time:sleepForMs, unit:'MILLISECONDS')

                                        String timeSinceStartSec = Math.round((currentBuild.duration - startAt)/1000)

                                        String remoteServerStats = sh(
                                                script: "curl --max-time 5 https://${remoteServer}/statistics",
                                                returnStdout: true,
                                            ).trim()
                                        println("https://${remoteServer}/statistics\n${remoteServerStats}")
                                        Object remoteStats = new groovy.json.JsonSlurperClassic().parseText(remoteServerStats)
                                        String localServerStats = sh(
                                                script: "curl --max-time 5 http://127.0.0.1:3003/statistics",
                                                returnStdout: true,
                                            ).trim()
                                        println("http://127.0.0.1:3003/statistics\n${localServerStats}")
                                        Object localStats = new groovy.json.JsonSlurperClassic().parseText(localServerStats)

                                        if (!chainStatusConnected) {
                                            if (localStats.statistics.status == "CHAIN_STATUS_CONNECTED") {
                                                chainStatusConnected = true
                                                currTime = currentBuild.durationString - ' and counting'
                                                println("Node has reached status CHAIN_STATUS_CONNECTED !! (${currTime})")
                                            }
                                        }
                                        if (chainStatusConnected) {
                                            int remoteHeight = remoteStats.statistics.blockHeight.toInteger()
                                            int localHeight = localStats.statistics.blockHeight.toInteger()

                                            if (!blockHeightIncreased) {
                                                if (localHeight > 0) {
                                                    if (previousLocalHeight < 0) {
                                                        previousLocalHeight = localHeight
                                                    } else if (localHeight > previousLocalHeight) {
                                                        blockHeightIncreased = true
                                                        currTime = currentBuild.durationString - ' and counting'
                                                        println("Detected that block has increased from ${previousLocalHeight} to ${localHeight} (${currTime})")
                                                    }
                                                }
                                            }

                                            if (!caughtUp && (remoteHeight - localHeight < 10)) {
                                                caughtUp = true
                                                catchupTime = currentBuild.durationString - ' and counting'
                                                println("Node has caught up with the vega network !! (heights local: ${localHeight}, remote: ${remoteHeight}) (${catchupTime})")
                                            }
                                        }
                                    }
                                }
                            },
                            'Info': {
                                echo "Jenkins Agent Public IP: ${jenkinsAgentPublicIP}. Some useful links:"
                                echo "http://${jenkinsAgentPublicIP}:3003/statistics"
                                echo "https://${remoteServer}/statistics"
                                echo "https://tm.${remoteServer}/net_info"
                            }
                        )
                    }
                    stage("Verify checks") {
                        if (!chainStatusConnected) {
                            extraMsg = extraMsg ?: "Not reached CHAIN_STATUS_CONNECTED."
                            error("Non-validator never reached CHAIN_STATUS_CONNECTED status.")
                        }
                        if (!blockHeightIncreased) {
                            extraMsg = extraMsg ?: "block height didn't increase."
                            error("Non-validator block height did not incrase.")
                        }
                        if (!caughtUp) {
                            extraMsg = extraMsg ?: "didn't catch up with network."
                            error("Non-validator did not catch up.")
                        }
                        println("All checks passed.")
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
                    sendSlackMessage(env.NET_NAME, extraMsg, catchupTime)
                }
            }
        }
    }
}

boolean nicelyStopAfter(String timeoutMin, Closure body) {
    int startTimeMs = currentBuild.duration
    catchError(
        message: "Timed task",
        buildResult: 'SUCCESS', // don't modify Build Status
        stageResult: 'SUCCESS', // keep Stage status Successful
        catchInterruptions: true, // timeout is FlowInterruptedException
    ) {
        timeout(time: timeoutMin, unit: 'MINUTES') {
            try {
                body()
            } catch (FlowInterruptedException e) {
                currentBuild.result = "SUCCESS"
            }
        }
    }
    return ( timeoutMin.toInteger() * 60 - 5 ) * 1000 < (currentBuild.duration - startTimeMs)
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

void sendSlackMessage(String vegaNetwork, String extraMsg, String catchupTime) {
    String slackChannel = '#snapshot-notify'
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
    } else {
        msg = ":red_circle: Snapshot testing (${vegaNetwork}) - FAILED - <${jobURL}|${jobName}>"
        color = 'danger'
    }

    if (catchupTime != null) {
        msg += " (catch up in ${catchupTime})"
    }

    if (extraMsg != null) {
        msg += " (reason: ${extraMsg})"
    }

    msg += " (${duration})"

    echo "${msg}"

    slackSend(
        channel: slackChannel,
        color: color,
        message: msg,
    )
}
