library (
    identifier: "vega-shared-library@lnl-pipeline",
    changelog: false,
)

capsuleSystemTests([
    vegacapsuleConfig: 'mainnet_config.hcl',
    systemTestsBranch: 'lnl-pipeline',
    hooks: [
        postNetworkGenerate: [
            'Load mainnet checkpoint': {
                def sshCredentials = sshUserPrivateKey(
                    credentialsId: 'ssh-vega-network',
                    keyFileVariable: 'PSSH_KEYFILE',
                    usernameVariable: 'PSSH_USER'
                )

                withCredentials([sshCredentials]) {
                    String networkDir = ""
                    String systemTestsDir = ""
                    dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                        networkDir = vegautils.escapePath(pwd())
                    }
                    
                    sh label: 'Prepare mainnet genesis', script: '''mkdir -p ./lnl-workdir;
                        devopsscripts lnl prepare-network \
                            --checkpoint-server-checkpoint-dir "/home/vega/vega_volume/vega/state/node/checkpoints" \
                            --checkpoint-server-host "mainnet-observer.ops.vega.xyz" \
                            --checkpoint-server-key-file "''' + PSSH_KEYFILE + '''" \
                            --checkpoint-server-user "''' + PSSH_USER + '''" \
                            --genesis-uri "file://system-tests/vegacapsule/net_configs/mainnet/genesis.json" \
                            --vegacapsule-network-home "''' + networkDir + '''/testnet" \
                            --out-dir "./lnl-workdir" \
                            --vegacapsule-path "vegacapsule" \
                            --vegatools-path "vegatools" \
                            --no-secrets
                    '''

                    sh label: 'Copy loaded checkpoint into system-tests directory', script: '''
                        cp ./lnl-workdir/checkpoint.json system-tests/tests/LNL/checkpoint-resume.json
                    '''
                }
            }
        ],
        runTests: [
            'Copy checkpoint after load': {
                String networkDir = ""
                dir (pipelineDefaults.capsuleSystemTests.systemTestsNetworkDir) {
                    networkDir = vegautils.escapePath(pwd())
                }

                rawPath = sh(returnStdout:true,
                    script: '''vegacapsule nodes wait-for-checkpoint \
                        --search-from-beginning \
                        --timeout 10m \
                        --print-last-checkpoint-path-only \
                        --checkpoints 1 \
                        --home-path="''' + networkDir + '''/testnet"
                    '''
                ).trim()
                checkpointPath = vegautils.escapePath(rawPath)
                sh label: 'Copy found checkpoint', 
                script: '''vegatools checkpoint \
                    --file "''' + checkpointPath + '''" \
                    --out system-tests/tests/LNL/after_checkpoint_load.json \
                    1> /dev/null
                '''
            }
        ],
        postPipeline: [
            'Archive checkpoints and genesis': {
                [
                    'system-tests/tests/LNL/checkpoint-resume.json',
                    'system-tests/tests/LNL/after_checkpoint_load.json',
                    'system-tests/vegacapsule/net_configs/mainnet/genesis.json'
                ].each {
                    if (fileExists(it)) {
                        archiveArtifacts(
                            artifacts: it,
                            allowEmptyArchive: true
                        )
                    } else {
                        print('[WARN] Artifact ' + it + ' not found. Archive skipped')
                    }
                }
            }
        ]
    ],
], customParams)