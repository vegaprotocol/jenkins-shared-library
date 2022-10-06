library (
    identifier: "vega-shared-library@lnl-pipeline",
    changelog: false,
)

customParams = [
    ORIGIN_REPO: 'vegaprotocol/vega',
    VEGA_BRANCH: 'develop',
    SYSTEM_TESTS_BRANCH: 'lnl-pipeline',
    VEGACAPSULE_BRANCH: 'lnl-pipeline',
    VEGATOOLS_BRANCH: 'develop',
    DEVOPS_INFRA_BRANCH: 'master',
    DEVOPSSCRIPTS_BRANCH: 'lnl-pipeline',
    SYSTEM_TESTS_DEBUG: false,
    TIMEOUT: 600,
    PRINT_NETWORK_LOGS: false,
    SYSTEM_TESTS_TEST_FUNCTION: 'test_checkpoint_loaded',
    SYSTEM_TESTS_TEST_MARK: '',
    SYSTEM_TESTS_TEST_DIRECTORY: 'tests/LNL',
    TEST_EXTRA_PYTEST_ARGS: '',
    CAPSULE_CONFIG: '',

    SKIP_MULTISIGN_SETUP: true,
]

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
        postRunTests: [
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
                    }
                }
            }
        ]
    ],
], customParams)