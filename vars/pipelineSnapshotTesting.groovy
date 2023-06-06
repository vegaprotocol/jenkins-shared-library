/* groovylint-disable DuplicateStringLiteral, ImplicitClosureParameter, LineLength */
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
    String remoteServerDataNode
    String remoteServerCometBFT
    String jenkinsAgentPublicIP

    // api output placeholders
    def TM_VERSION
    def RPC_SERVERS
    def SEEDS
    def SNAPSHOT_HEIGHT
    def SNAPSHOT_HASH
    def NETWORK_HISTORY_PEERS

    // Checks
    String extraMsg = null  // extra message to print on Slack. In case of multiple message, keep only first.
    String catchupTime = null
    boolean chainStatusConnected = false
    boolean caughtUp = false
    boolean blockHeightIncreased = false
    String networkVersion = null

    node(params.NODE_LABEL) {
        timestamps {

            stage('init') {
                skipDefaultCheckout()
                cleanWs()
                sh 'if [ ! -z "$(docker ps -q)" ]; then docker kill $(docker ps -q); fi'
            }

            try {
                // give extra 5 minutes for setup
                timeout(time: params.TIMEOUT.toInteger() + 5, unit: 'MINUTES') {
                    stage('CI config') {
                        // Printout all configuration variables
                        sh 'printenv'
                        echo "params=${params.inspect()}"
                        // digitalocean
                        if (params.NODE_LABEL == "s-4vcpu-8gb") {
                            jenkinsAgentPublicIP = sh(
                                script: 'curl --max-time 3 -sL http://169.254.169.254/metadata/v1.json | jq -Mrc ".interfaces.public[0].ipv4.ip_address"',
                                returnStdout: true,
                            ).trim()
                        }
                        // aws
                        else {
                            jenkinsAgentPublicIP = sh(
                                script: 'curl --max-time 3 http://169.254.169.254/latest/meta-data/public-ipv4',
                                returnStdout: true,
                            ).trim()
                        }
                        echo "jenkinsAgentPublicIP=${jenkinsAgentPublicIP}"
                        if (!jenkinsAgentPublicIP) {
                            error("Couldn't resolve jenkinsAgentPublicIP")
                        }
                    }

                    stage('Find available remote server') {
                        def baseDomain = "${env.NET_NAME}.vega.xyz"
                        if (env.NET_NAME == "fairground") {
                            baseDomain = "testnet.vega.xyz"
                        }
                        def networkServers = (0..15).collect { "n${it.toString().padLeft( 2, '0' )}.${baseDomain}" }
                        if (env.NET_NAME == "mainnet") {
                            networkServers = [
                                "api0.vega.community",
                                "api1.vega.community",
                                "api2.vega.community",
                                "api3.vega.community",
                                "api4.vega.community",
                                "api5.vega.community",
                                "api6.vega.community",
                                "api7.vega.community",
                            ]
                        }

                        // exclude from checking servers that are in the denylist configured in DSL
                        if (env.NODES_DENYLIST) {
                            def denyList = (env.NODES_DENYLIST as String).split(',')
                            networkServers = networkServers.findAll{ server -> !denyList.contains(server) }
                        }

                        Collections.shuffle(networkServers as List)

                        echo "Going to check servers: ${networkServers}"
                        // Need to check Data Node endpoint
                        if (env.NET_NAME == "mainnet") {
                            remoteServer = networkServers.find{ serverName -> isRemoteServerAlive(serverName) }
                        } else {
                            remoteServer = networkServers.find{ serverName -> isRemoteServerAlive("api.${serverName}") }
                        }
                        if ( remoteServer == null ) {
                            // No single machine online means that Vega Network is down
                            // This is quite often for Devnet, when deployments happen all the time
                            extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                            currentBuild.result = 'ABORTED'
                            error("${env.NET_NAME} seems down")
                        }
                        if (env.NET_NAME == "mainnet") {
                            remoteServerDataNode = remoteServer
                            remoteServerCometBFT = "tm.${remoteServer}"
                        } else {
                            remoteServerDataNode = "api.${remoteServer}"
                            remoteServerCometBFT = "tm.${remoteServer}"
                        }
                        echo "Found available server: ${remoteServerDataNode} (${remoteServerCometBFT})"
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
                                    sh label: "scp data node config from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServerDataNode}\":/home/vega/vega_home/config/data-node/config.toml data-node-config.toml
                                        """
                                }
                                NETWORK_HISTORY_PEERS = sh(
                                    label: 'read persistent peers',
                                    script: "./dasel -f data-node-config.toml -w json -c ${env.HISTORY_KEY}.Store.BootstrapPeers",
                                    returnStdout: true
                                ).trim()
                                echo "NETWORK_HISTORY_PEERS=${NETWORK_HISTORY_PEERS}"
                            },
                            'vega core binary': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp vega core from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServerDataNode}\":/home/vega/vegavisor_home/current/vega vega
                                        """
                                    sh label: "vega version", script: """#!/bin/bash -e
                                        ./vega version
                                    """
                                }
                            },
                            'genesis.json': {
                                withCredentials([sshDevnetCredentials]) {
                                    sh label: "scp genesis.json from ${remoteServerDataNode}",
                                        script: """#!/bin/bash -e
                                            scp -i \"\${PSSH_KEYFILE}\" \"\${PSSH_USER}\"@\"${remoteServerDataNode}\":/home/vega/tendermint_home/config/genesis.json genesis.json
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
                            def snapshot_req = new URL("https://${remoteServerDataNode}/api/v2/snapshots").openConnection()
                            def snapshot = new groovy.json.JsonSlurperClassic().parseText(snapshot_req.getInputStream().getText())
                            def snapshotInfo = snapshot['coreSnapshots']['edges'][0]['node']
                            SNAPSHOT_HEIGHT = snapshotInfo['blockHeight']
                            SNAPSHOT_HASH = snapshotInfo['blockHash']
                            println("SNAPSHOT_HEIGHT='${SNAPSHOT_HEIGHT}' - also used as trusted block height in tendermint statesync config")
                            println("SNAPSHOT_HASH='${SNAPSHOT_HASH}' - also used as trusted block hash in tendermint statesync config")

                            // Check TM version
                            def status_req = new URL("https://${remoteServerCometBFT}/status").openConnection()
                            def status = new groovy.json.JsonSlurperClassic().parseText(status_req.getInputStream().getText())
                            TM_VERSION = status.result.node_info.version
                            println("TM_VERSION=${TM_VERSION}")

                            // Get data from TM
                            (SEEDS, RPC_SERVERS) = getSeedsAndRPCServers(remoteServerCometBFT)
                            Collections.shuffle(SEEDS as List)
                            Collections.shuffle(RPC_SERVERS as List)
                            SEEDS = SEEDS.take(2)
                            RPC_SERVERS = RPC_SERVERS.take(2)
                            println("SEEDS=${SEEDS}")
                            println("RPC_SERVERS=${RPC_SERVERS}")

                        } catch (e) {
                            if ( !isRemoteServerAlive(remoteServerDataNode) ) {
                                // Remote server stopped being available.
                                // This is quite often for Devnet, when deployments happen all the time
                                extraMsg = extraMsg ?: "${env.NET_NAME} seems down. Snapshot test aborted."
                                currentBuild.result = 'ABORTED'
                                error("${env.NET_NAME} seems down")
                            } else {
                                println("Remote server ${remoteServerDataNode} is still up.")
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
                                        ./dasel put string -f tm_config/config/config.toml statesync.trust_hash ${SNAPSHOT_HASH}
                                        ./dasel put int -f tm_config/config/config.toml statesync.trust_height ${SNAPSHOT_HEIGHT}
                                        ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers ${RPC_SERVERS}
                                        ./dasel put string -f tm_config/config/config.toml statesync.discovery_time "30s"
                                        ./dasel put string -f tm_config/config/config.toml statesync.chunk_request_timeout "30s"
                                        ./dasel put string -f tm_config/config/config.toml p2p.seeds ${SEEDS}
                                        ./dasel put int -f tm_config/config/config.toml p2p.max_packet_msg_payload_size 16384
                                        ./dasel put string -f tm_config/config/config.toml p2p.external_address "${jenkinsAgentPublicIP}:26656"
                                        ./dasel put bool -f tm_config/config/config.toml p2p.allow_duplicate_ip true
                                    """
                                if (env.NET_NAME == 'validators-testnet') {
                                    sh label: 'set Tendermint config (validators-testnet specific)',
                                        script: """#!/bin/bash -e
                                            ./dasel put string -f tm_config/config/config.toml statesync.rpc_servers "sn012.validators-testnet.vega.xyz:40127,sn011.validators-testnet.vega.xyz:40117"
                                        """
                                }
                                sh label: 'print tendermint config',
                                    script: '''#!/bin/bash -e
                                        cat tm_config/config/config.toml
                                    '''
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
                                        ./dasel put bool -f vega_config/config/data-node/config.toml AutoInitialiseFrom${env.HISTORY_KEY} true
                                        ./dasel put bool -f vega_config/config/data-node/config.toml SQLStore.UseEmbedded false
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Host 127.0.0.1
                                        ./dasel put int -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Port 5432
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Username vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Password vega
                                        ./dasel put string -f vega_config/config/data-node/config.toml SQLStore.ConnectionConfig.Database vega
                                        sed -i 's|.*BootstrapPeers.*|    BootstrapPeers = ${NETWORK_HISTORY_PEERS}|g' vega_config/config/data-node/config.toml
                                        cat vega_config/config/data-node/config.toml
                                    """
                                    // ^ easier to use sed rather than dasel. number of spaces is hardcoded and NETWORK_HISTORY_PEERS var is in toml compatible format (minimized JSON)
                            },
                            'postgres': {
                                // jenkins needs to be added to postgres image and be added to postgres group so it's files loaded from actual workspace by volume binding are able to be loaded into database
                                // to do we overwrite entrypoint, provision user and then restore original entrypoint
                                // it can be found here in the layers history (combination of ENTRYPOINT and CMD) -> https://hub.docker.com/layers/timescale/timescaledb/latest-pg14/images/sha256-73ec414f1dd66eec68682da9c0c689574cecf5fbe1683f649c77233bb83c30f2?context=explore
                                UID = sh(
                                    script: 'id -u $(whoami)',
                                    returnStdout: true
                                ).trim()
                                writeFile(
                                    file: 'start-postgres.sh',
                                    text: """#!/bin/bash -ex
                                        whoami
                                        adduser jenkins -D -u ${UID} -G postgres
                                        chmod -R a+rwx /jenkins/workspace/
                                        bash docker-entrypoint.sh postgres
                                    """
                                )
                                sh 'cat start-postgres.sh; chmod +x start-postgres.sh'
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
                                                -v $PWD/start-postgres.sh:/start-postgres.sh \
                                                -u root \
                                                --entrypoint '/start-postgres.sh' \
                                                -p 5432:5432 \
                                                    timescale/timescaledb:latest-pg14
                                        '''
                                }
                            },
                            'Data node': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    // wait for db
                                    sleep(time: '30', unit:'SECONDS')
                                    sh label: 'run data node',
                                        script: """#!/bin/bash -e
                                            ./vega datanode start --home=vega_config
                                        """
                                }
                            },
                            'Vega': {
                                boolean nice = nicelyStopAfter(params.TIMEOUT) {
                                    sleep(time: '35', unit:'SECONDS')
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
                                if ( !nice && isRemoteServerAlive(remoteServerDataNode) ) {
                                    extraMsg = extraMsg ?: "Vega core stopped too early."
                                    error("Vega stopped too early, Remote Server is still alive.")
                                }
                            },
                            'Checks': {
                                nicelyStopAfter(params.TIMEOUT) {
                                    sleep(time: '60', unit:'SECONDS')
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
                                                script: "curl --max-time 5 https://${remoteServerDataNode}/statistics || echo '{}'",
                                                returnStdout: true,
                                            ).trim()
                                        println("https://${remoteServerDataNode}/statistics\n${remoteServerStats}")
                                        Object remoteStats = new groovy.json.JsonSlurperClassic().parseText(remoteServerStats)
                                        String localServerStats = sh(
                                                script: "curl --max-time 5 http://127.0.0.1:3003/statistics || echo '{}'",
                                                returnStdout: true,
                                            ).trim()
                                        println("http://127.0.0.1:3003/statistics\n${localServerStats}")
                                        Object localStats = new groovy.json.JsonSlurperClassic().parseText(localServerStats)

                                        if (networkVersion == null || networkVersion.length() < 1) {
                                            networkVersion = localStats?.statistics?.appVersion
                                        }

                                        if (!chainStatusConnected) {
                                            if (localStats?.statistics?.status == "CHAIN_STATUS_CONNECTED") {
                                                chainStatusConnected = true
                                                currTime = currentBuild.durationString - ' and counting'
                                                println("Node has reached status CHAIN_STATUS_CONNECTED !! (${currTime})")
                                            }
                                        }
                                        if (chainStatusConnected) {
                                            int remoteHeight = remoteStats?.statistics?.blockHeight?.toInteger() ?: 0
                                            int localHeight = localStats?.statistics?.blockHeight?.toInteger() ?: 0

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

                                            if (!caughtUp && remoteHeight != 0 && (remoteHeight - localHeight < 10)) {
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
                                echo "http://${jenkinsAgentPublicIP}:3008/graphql/"
                                echo "http://${jenkinsAgentPublicIP}:3008/api/v2/epoch"
                                echo "https://${remoteServerDataNode}/statistics"
                                echo "https://${remoteServerCometBFT}/net_info"
                            }
                        )
                    }
                    stage("Verify checks") {
                        if (!chainStatusConnected) {
                            extraMsg = extraMsg ?: "Not reached CHAIN_STATUS_CONNECTED."
                            error("Non-validator never reached CHAIN_STATUS_CONNECTED status.")
                        }
                        echo "Chain status connected: ${chainStatusConnected}"
                        if (!blockHeightIncreased) {
                            extraMsg = extraMsg ?: "block height didn't increase."
                            error("Non-validator block height did not incrase.")
                        }
                        echo "Block height increased: ${blockHeightIncreased}"
                        if (!caughtUp) {
                            extraMsg = extraMsg ?: "didn't catch up with network."
                            error("Non-validator did not catch up.")
                        }
                        echo "Caught up: ${caughtUp}"
                        println("All checks passed.")
                    }
                }

                stage('Backup snapshots') {
                    if (params.BACKUP_SNAPSHOTS) {
                        gitClone(
                            url: 'git@github.com:vegaprotocol/snapshot-backups.git',
                            branch: 'main',
                            directory: 'snapshot-backups',
                            credentialsId: 'vega-ci-bot',
                            timeout: 3,
                        )

                        // Example output:
                        // { "snapshots":[
                        //   {
                        //     "height":774000,
                        //     "version":774,"size":88,
                        //     "hash":"449ac952c29c615fcf8ea9ee14ffe056fec034740b3a879c9c6818563f83fb75"
                        //   },
                        //   {"height":773000,...},
                        //   ...
                        // ]}
                        String snapshotsDetailsJSON = vegautils.shellOutput('''./vega tools snapshot \
                                --db-path ./vega_config/state/node/snapshots \
                                --output json''')

                        Map snapshotDetails = readJSON text: snapshotsDetailsJSON

                        List snapshotsHeight = snapshotDetails?.snapshots.collect{ it?.height as int }

                        sh 'mkdir -p snapshot-backups/' + env.NET_NAME + '/' + networkVersion + '/' + snapshotsHeight.min() + '-' + snapshotsHeight.max()

                        sh 'tar -czvf ' + snapshotsHeight.min() + '-' + snapshotsHeight.max() + '.tar.gz vega_config/state/node/snapshots'
                        sh '''cp ''' + snapshotsHeight.min() + '''-''' + snapshotsHeight.max() + '''.tar.gz \
                            snapshot-backups/''' + env.NET_NAME + '''/''' + networkVersion + '''/''' + snapshotsHeight.min() + '''-''' + snapshotsHeight.max()

                        makeCommit(
                            makeCheckout: true,
                            directory: 'snapshot-backups',
                            url: 'git@github.com:vegaprotocol/snapshot-backups.git',
                            branchName: 'snapshot-' + env.NET_NAME + '-' + snapshotsHeight.min() + '-' + snapshotsHeight.max(),
                            commitMessage: '[Automated] Snapshot backup for ' + env.NET_NAME + ', blocks ' + snapshotsHeight.min() + '-' + snapshotsHeight.max(),
                            commitAction: 'git add ' + env.NET_NAME + '/' + networkVersion + '/' + snapshotsHeight.min() + '-' + snapshotsHeight.max()
                        )
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

boolean isRemoteServerAlive(String serverURL) {
    try {
        def statistics_req = new URL("https://${serverURL}/statistics").openConnection()
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

boolean checkServerListening(String serverHost, int serverPort) {
  timeoutMs = 1000
  Socket s = null
  try {
    s = new Socket()
    s.connect(new InetSocketAddress(serverHost, serverPort), timeoutMs);
    return true
  } catch (Exception e) {
    return false
  } finally {
    if(s != null) {
    try {s.close();}
    catch(Exception e){}
    }
  }
}

def getSeedsAndRPCServers(String cometURL) {
  def net_info_req = new URL("https://${cometURL}/net_info").openConnection()
  def net_info = new groovy.json.JsonSlurperClassic().parseText(net_info_req.getInputStream().getText())
  
  RPC_SERVERS = []
  SEEDS = []
  for(peer in net_info.result.peers) {
    // Get domain/IP address and port
    def addr = peer.node_info.listen_addr.minus('tcp://').split(":")
    if(addr.size()<2) {
      continue
    }
    def port = addr[1] as int
    addr = addr[0]
    if(["0.0.0.0", "127.0.0.1"].contains(addr)) {
      continue
    }
    if( ! checkServerListening(addr, port)) {
      continue
    }
    // Get RPC port 
    def rpc_port = peer.node_info.other.rpc_address.minus('tcp://').split(":")
    if(rpc_port.size()<2) {
      continue
    }
    rpc_port = rpc_port[1] as int
    if( ! checkServerListening(addr, rpc_port)) {
      continue
    }
    // Get Peer ID
    def comet_peer_id = peer.node_info.id
    // Append result lists
    SEEDS.add("${comet_peer_id}@${addr}:${port}")
    RPC_SERVERS.add("${addr}:${rpc_port}")
  }
  new Tuple2(SEEDS, RPC_SERVERS)
}
