library (
    identifier: "vega-shared-library@lnl-pipeline",
    changelog: false,
)

customParams = [
    ORIGIN_REPO: 'vegaprotocol/vega',
    VEGA_BRANCH: 'develop',
    SYSTEM_TESTS_BRANCH: 'develop',
    VEGACAPSULE_BRANCH: 'main',
    VEGATOOLS_BRANCH: 'develop',
    DEVOPS_INFRA_BRANCH: 'master',
    DEVOPSSCRIPTS_BRANCH: 'lnl-pipeline',
    SYSTEM_TESTS_DEBUG: false,
    TIMEOUT: 600,
    PRINT_NETWORK_LOGS: false,
    SYSTEM_TESTS_TEST_FUNCTION: '',
    SYSTEM_TESTS_TEST_MARK: 'smoke',
    SYSTEM_TESTS_TEST_DIRECTORY: '',
    TEST_EXTRA_PYTEST_ARGS: '',
    TEST_DIRECTORY: '',
    CAPSULE_CONFIG: '',

    // TOOD: Add it to dsl
    SKIP_MULTISIGN_SETUP: true,
]

capsuleSystemTests([
    vegacapsuleConfig: 'mainnet_config.hcl',
    systemTestsBranch: 'lnl-pipeline',

    postNetworkGenerateStages: [
        'Load mainnet checkpoint': {
            def sshCredentials = sshUserPrivateKey(
                credentialsId: 'ssh-vega-network',
                keyFileVariable: 'PSSH_KEYFILE',
                usernameVariable: 'PSSH_USER'
            )

            withCredentials([sshCredentials]) {
                String networkDir = ""
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
            }
        }
    ],
    systemTestsSiblingsStages: [
        'daniel-test': {
            sleep 36000
        }
    ],
    postNetworkStartStages: [:]
], customParams)