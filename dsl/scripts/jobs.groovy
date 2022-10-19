/* groovylint-disable CompileStatic, DuplicateNumberLiteral, DuplicateStringLiteral, LineLength */
// https://github.com/janinko/ghprb/issues/77
def scmDefinition(args){
  return {
    cpsScm {
      scm {
        git {
          if (args.branch) {
              branch("*/${args.branch}")
          }
          remote {
            url(args.repo)
            credentials(args.get('credentials', "vega-ci-bot"))
            if (args.branch) {
                refspec("+refs/heads/${args.branch}:refs/remotes/origin/${args.branch}")
            }
          }
        }
      }
      scriptPath(args.get('jenkinsfile', 'Jenkinsfile'))
    }
  }
}

def h(def text, def num=4) {
    return "<h${num}>${text}</h${num}>"
}

def ul(def ulMap) {
    def entries = ulMap.collect { "<li>${it.key} - ${it.value}</li>" }.join("\n")
    return "<ul>${entries}</ul>"
}


def standardDescription() {
    def url = "https://github.com/vegaprotocol/jenkins-shared-library/tree/main/dsl"
    return h("""
        This job was automatically generated by DSL script located at <a href="${url}">this repository</a> and processed by <a href='${binding.variables.get('JOB_URL')}'>this job</a>, any manual configuration will be overriden.
    """, 5)
}


def createCommonPipeline(args){
    args.repo = "git@github.com:vegaprotocol/${args.repo}.git"

    def des = args.get('description', '')
    des += "${des ? '<br/>' : ''} ${standardDescription()}"

    return pipelineJob(args.name) {

        disabled(args.get('disabled', false))

        description(des)

        logRotator {
            daysToKeep(args.daysToKeep ?: 45)
            numToKeep(args.numToKeep ?: 1000)
            artifactDaysToKeep(args.daysToKeep ?: 45)
            artifactNumToKeep(args.numToKeep ?: 1000)
        }

        if (args.parameters) {
            parameters args.parameters
        }

        environmentVariables {
            keepBuildVariables(true)
            keepSystemVariables(true)
            args.env.each { key, value ->
                env(key.toUpperCase(), value)
            }
        }

        if (args.get('useScmDefinition', true)) {
            definition scmDefinition(args)
        }
        else {
            definition args.definition
        }

        properties {
            if (args.copyArtifacts) {
                copyArtifactPermission {
                    projectNames('*')
                }
            }
            if (args.disableConcurrentBuilds) {
                disableConcurrentBuilds {
                    abortPrevious(args.abortPrevious ?: false)
                }
            }
            pipelineTriggers {
                triggers {
                    if (args.get('useScmDefinition', true)) {
                        githubPush()
                    }
                    if (args.cron) {
                        cron {
                            spec(args.cron)
                        }
                    }
                    if (args.parameterizedCron) {
                        parameterizedCron {
                            parameterizedSpecification(args.parameterizedCron)
                        }
                    }
                }
            }
        }
    }
}

def libDefinition(methodName) {
    return {
        cps {
            script('''
            library (
                identifier: "vega-shared-library@${env.JENKINS_SHARED_LIB_BRANCH}",
                changelog: false,
            )

            ''' + methodName)
        }
    }
}

def capsuleParams() {
    return {
        booleanParam('BUILD_CAPSULE', true, h('decide if build vegacapsule from source if false VEGACAPSULE_VERSION will be looked up in releases page', 5))
        stringParam('VEGACAPSULE_VERSION', 'main', h('version of vegacapsule (tag, branch, any revision)'))
        stringParam('VEGA_VERSION', '', h('version of vega core (tag, branch, commit or S3 path)'))
        booleanParam('BUILD_VEGA_BINARIES', false, h('determine whether vega binaries are built or downloaded'))
        booleanParam('PUBLISH_BINARIES', false, h('determine whether binaries are published to S3'))
        stringParam('DATA_NODE_VERSION', '', h('version of data node (binary tag, or S3 path)'))
        choiceParam('ACTION', ['RESTART', 'START', 'STOP'], h('action to be performed with network'))
        booleanParam('REGENERATE_CONFIGS', false, h('check this to regenerate network configs with capsule', 5))
        booleanParam('UNSAFE_RESET_ALL', false, h('decide if vegacapsule should perform unsafe-reset-all on RESTART action', 5))
        stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        stringParam('DEVOPS_INFRA_VERSION', 'master', h('version of the devops-infra repository (tag, branch, any revision)'))
        booleanParam('CREATE_MARKETS', true, h('create markets using veganet.sh'))
        booleanParam('BOUNCE_BOTS', true, h('bounce bots using veganet.sh - Start & Top up liqbot and traderbot with fake/ERC20 tokens'))
    }
}

def vegavisorParamsBase() {
    return {
        stringParam('VEGACAPSULE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository')
        stringParam('DEVOPSTOOLS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopstools repository')
        stringParam('ANSIBLE_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/ansible repository')
        stringParam('NETWORKS_INTERNAL_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/networks-internal repository')
        stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
    }
}

def vegavisorRestartNetworkParams(args=[:]) {
    def choices = [
        'restart-network': 'regular restart',
        'quick-restart-network': 'fast restart without config updates',
        'create-network': 'reset network',
        'stop-network': 'stop entire network',
    ]
    return vegavisorParamsBase() << {
        choiceParam('ACTION', choices.keySet() as List, h('action to be performed with a network') + ul(choices))
        stringParam('VEGA_VERSION', '', '''Specify which version of vega to deploy. Leave empty to restart network only.
        Provide git branch, tag or hash of the vegaprotocol/vega repository or leave empty''')
        stringParam('RELEASE_VERSION', '', 'Specify which version of vega to deploy. Leave empty to restart network only.')
        stringParam('DOCKER_VERSION', '', 'Specify which version of docker images to deploy. Leave empty to not change.')
        booleanParam('UNSAFE_RESET_ALL', true, 'If set to true then delete all local state. Otherwise leave it for restart.')
        booleanParam('USE_CHECKPOINT', args.get('USE_CHECKPOINT', false), 'This will download latest checkpoint and use it to restart the network with')
        booleanParam('CREATE_MARKETS', args.get('CREATE_MARKETS', false), h('create markets using veganet.sh'))
        booleanParam('TOP_UP_BOTS', args.get('TOP_UP_BOTS', false), h('trigger top up job'))
        stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
        stringParam('CHECKPOINT_STORE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/checkpoint-store repository')
    }
}

def vegavisorRestartNodeParams(args=[:]) {
    def choices = [
        'restart-node': 'regular restart',
        'quick-restart-node': 'fast restart without config updates',
        'create-node': 'reset node',
        'stop-node': 'stop node',
        'recreate-node': 'wipe node data and set it up again',
    ]
    return vegavisorParamsBase() << {
        choiceParam('ACTION', choices.keySet() as List, h('action to be performed with a node') + ul(choices) )
        booleanParam('UNSAFE_RESET_ALL', false, 'If set to true then delete all local node state. Otherwise leave it for restart.')
        booleanParam('RANDOM_NODE', false, 'If set to true restart random node instead of the one provided in the parameters.')
        stringParam('VEGA_VERSION', '', '''Specify which version of vega to deploy. Leave empty to restart network only.
        Provide git branch, tag or hash of the vegaprotocol/vega repository or leave empty''')
        stringParam('RELEASE_VERSION', '', 'Specify which version of vega to deploy. Leave empty to restart network only.')
        choiceParam('NODE', (0..15).collect { "n${it.toString().padLeft( 2, '0' )}.${args.name}.vega.xyz" }, 'Choose which node to restart')
    }
}

def vegavisorProtocolUpgradeParams() {
    return vegavisorParamsBase() << {
        stringParam('UPGRADE_BLOCK', '', 'Protocol upgrade block. Leave empty to use: current block + 200')
        stringParam('RELEASE_VERSION', '', 'Specify which version of vega to deploy. Leave empty to restart network only.')
        booleanParam('MANUAL_INSTALL', true, 'If true, then config and binaries are uploaded manualy before protocol upgrade. When false, then visor automatically create everything.')
    }
}

def systemTestsParamsGeneric(args=[:]) {
    return {
        stringParam('ORIGIN_REPO', 'vegaprotocol/vega', 'repository which acts as vega source code (used for forks builds)')
        stringParam('VEGA_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/vega repository')
        stringParam('SYSTEM_TESTS_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/system-tests repository')
        stringParam('VEGACAPSULE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository')
        stringParam('VEGATOOLS_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/vegatools repository')
        stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
        stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
        stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        booleanParam('SYSTEM_TESTS_DEBUG', false, 'Enable debug logs for system-tests execution')
        stringParam('TIMEOUT', '300', 'Timeout in minutes, after which the pipline is force stopped.')
        booleanParam('PRINT_NETWORK_LOGS', false, 'By default logs are only archived as as Jenkins Pipeline artifact. If this is checked, the logs will be printed in jenkins as well')
        if (args.get('SCENARIO', false)){
            choiceParam('SCENARIO', args.get('SCENARIO') == 'NIGHTLY' ? ['NIGHTLY', 'PR'] : ['PR', 'NIGHTLY'], 'Choose which scenario should be run, to see exact implementation of the scenario visit -> https://github.com/vegaprotocol/jenkins-shared-library/blob/main/vars/pipelineCapsuleSystemTests.groovy')
        }
    }
}

def systemTestsParamsWrapper() {
    return systemTestsParamsGeneric() << {
        stringParam('SYSTEM_TESTS_TEST_FUNCTION', '', 'Run only a tests with a specified function name. This is actually a "pytest -k $SYSTEM_TESTS_TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
        stringParam('SYSTEM_TESTS_TEST_MARK', 'smoke', 'Run only a tests with the specified mark(s). This is actually a "pytest -m $SYSTEM_TESTS_TEST_MARK" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
        stringParam('SYSTEM_TESTS_TEST_DIRECTORY', '', 'Run tests from files in this directory and all sub-directories')
        stringParam('TEST_EXTRA_PYTEST_ARGS', '', 'extra args passed to system tests executiom')
        stringParam('TEST_DIRECTORY', '', 'list or wildcard of files/directories to collect test files from')
        stringParam('CAPSULE_CONFIG', 'capsule_config.hcl', 'Run tests using the given vegacapsule config file')
        booleanParam('SKIP_MULTISIGN_SETUP', false, h('When true validators are not added to multisig as signers'))
    }
}


def lnlSystemTestsparams() {
    return systemTestsParamsGeneric() << {
        stringParam('SYSTEM_TESTS_TEST_FUNCTION', 'test_checkpoint_loaded', 'Run only a tests with a specified function name. This is actually a "pytest -k $SYSTEM_TESTS_TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
        stringParam('SYSTEM_TESTS_TEST_MARK', '', 'Run only a tests with the specified mark(s). This is actually a "pytest -m $SYSTEM_TESTS_TEST_MARK" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
        stringParam('SYSTEM_TESTS_TEST_DIRECTORY', 'tests/LNL', 'Run tests from files in this directory and all sub-directories')
        stringParam('TEST_EXTRA_PYTEST_ARGS', '', 'extra args passed to system tests executiom')
        stringParam('CAPSULE_CONFIG', 'capsule_config_mainnet.hcl', 'Run tests using the given vegacapsule config file')
        booleanParam('SKIP_MULTISIGN_SETUP', true, h('When true validators are not added to multisig as signers'))
    }
}

def approbationParams(def config=[:]) {
    return {
        if (config.type == 'core') {
            stringParam('ORIGIN_REPO', 'vegaprotocol/vega', 'repo which acts as source of vegaprotocol (used for forks builds)')
            stringParam('VEGA_CORE_BRANCH', 'develop', 'Git branch, tag or hash of the origin repo repository')
            stringParam('MULTISIG_CONTROL_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/MultisigControl repository')
            stringParam('SYSTEM_TESTS_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/system-tests repository')
        }
        else if (config.type == 'frontend') {
            stringParam('FRONTEND_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/frontend-monorepo repository')
            stringParam('VEGAWALLET_DESKTOP_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/vegawallet-desktop repository')
        }

        stringParam('SPECS_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/specs repository')

        if (config.type == 'core') {
            stringParam('SPECS_ARG', '{./specs/protocol/**/*.{md,ipynb},./specs/non-protocol-specs/**/*.{md,ipynb}}', '--specs argument value')
        }
        else if (config.type == 'frontend') {
            stringParam('SPECS_ARG', 'specs/user-interface/**/*.md', '--specs argument value')
        }

        if (config.type == 'core') {
            stringParam('TESTS_ARG',  '{./system-tests/tests/**/*.py,./vega/core/integration/**/*.{go,feature},./MultisigControl/test/*.js}', '--tests argument value')
        }
        else if (config.type == 'frontend') {
            stringParam('TESTS_ARG', '{frontend-monorepo/apps/*-e2e/**/*.cy.js,vegawallet-desktop/frontend/automation/cypress/**/*.cy.js}', '--tests argument value')
        }

        if (config.type == 'core' ) {
            stringParam('IGNORE_ARG','{./spec-internal/protocol/0060*,./specs/non-protocol-specs/{0001-NP*,0002-NP*,0004-NP*,0006-NP*,0007-NP*,0008-NP*,0010-NP*}}', '--ignore argument value' )
        }

        if (config.type == 'core') {
            stringParam('OTHER_ARG', '--show-branches --show-mystery --category-stats --show-files --verbose --output-csv --output-jenkins --show-file-stats',  'Other arguments')
        }
        else if (config.type == 'frontend') {
            stringParam('OTHER_ARG', '--categories="./specs/user-interface/categories.json" --category-stats --show-branches --verbose --show-files --output-jenkins', 'Other arguments')
        }

        stringParam('APPROBATION_VERSION', '2.7.1', 'Released version of Approbation. latest can be used')

        stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
    }
}

def jobs = [
    // Capsule playground
    [
        name: 'private/Deployments/Vegacapsule/Stagnet 3',
        useScmDefinition: false,
        parameters: capsuleParams(),
        definition: libDefinition('''pipelineDeployVegacapsule([
                networkName: 'stagnet3',
                nomadAddress: 'https://n00.stagnet3.vega.xyz:4646',
                awsRegion: 'eu-west-2',
                vegacapsuleS3BucketName: 'vegacapsule-20220722172637220400000001',
                networksInternalBranch: 'main',
                nomadNodesNumer: 8,
            ])'''),
        disableConcurrentBuilds: true,
        // weekdays 5AM UTC, jenkins prefred minute
        parameterizedCron: 'H 5 * * 1-5 %' + [
            'VEGA_VERSION=develop',
            'BUILD_VEGA_BINARIES=true',
            'UNSAFE_RESET_ALL=true',
            'REGENERATE_CONFIGS=true',
            'PUBLISH_BINARIES=true',
            'ACTION=RESTART',
            'CREATE_MARKETS=true',
            'BOUNCE_BOTS=true',
        ].join(';'),
    ],
    // DSL Job - the one that manages this file
    [
        name: 'private/DSL Job',
        repo: 'jenkins-shared-library',
        description: h('this job is used to generate other jobs'),
        jenkinsfile: 'dsl/Jenkinsfile',
        branch: 'main',
        disableConcurrentBuilds: true,
        numToKeep: 100,
    ],
    // Jenkins Configuration As Code
    [
        name: 'private/Jenkins Configuration as Code Pipeline',
        repo: 'jenkins-shared-library',
        description: h('This job is used to auto apply changes to jenkins instance configuration'),
        jenkinsfile: 'jcasc/Jenkinsfile',
        branch: 'main',
        disableConcurrentBuilds: true,
        numToKeep: 100,
    ],
    [
        name: 'private/Deployments/Publish-vega-dev-releases',
        description: h('This job builds vega binaries and publishes then as GitHub release to vega-dev-releases GitHub repo'),
        useScmDefinition: false,
        definition: libDefinition('pipelineVegaDevRelease()'),
        parameters: {
            stringParam('VEGA_VERSION', 'develop', 'Git branch, tag or hash of the vegaprotocol/vega repository')
            booleanParam('DEPLOY_TO_DEVNET_1', true, 'Trigger deployment to Devnet 1')
            booleanParam('DEPLOY_TO_STAGNET_1', false, 'Trigger deployment to Stagnet 1')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        disableConcurrentBuilds: true,
    ],
    //
    // Devnet 1
    //
    [
        name: 'private/Deployments/devnet1/Manage-Network',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNetwork()'),
        env: [
            NET_NAME: 'devnet1',
            ANSIBLE_LIMIT: 'devnet1',
            NETWORKS_INTERNAL_GENESIS_BRANCH: 'config-devnet1',
        ],
        parameters: vegavisorRestartNetworkParams(
            'CREATE_MARKETS': true,
            'TOP_UP_BOTS': true,
        ),
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/devnet1/Manage-Node',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNode()'),
        env: [
            NET_NAME: 'devnet1',
        ],
        parameters: vegavisorRestartNodeParams(name: 'devnet1'),
        disableConcurrentBuilds: true,
        // restart a random node every 30min
        parameterizedCron: 'H/30 * * * * %RANDOM_NODE=true',
    ],
    [
        name: 'private/Deployments/devnet1/Protocol-Upgrade',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorProtocolUpgradeNetwork()'),
        env: [
            NET_NAME: 'devnet1',
            ANSIBLE_LIMIT: 'devnet1',
        ],
        parameters: vegavisorProtocolUpgradeParams(),
        disableConcurrentBuilds: true,
    ],
    //
    // Sandbox
    //
    [
        name: 'private/Deployments/sandbox/Manage-Network',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNetwork()'),
        env: [
            NET_NAME: 'sandbox',
            ANSIBLE_LIMIT: 'sandbox',
            NETWORKS_INTERNAL_GENESIS_BRANCH: 'sandbox-config',
        ],
        parameters: vegavisorRestartNetworkParams(),
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/sandbox/Manage-Node',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNode()'),
        env: [
            NET_NAME: 'sandbox',
        ],
        parameters: vegavisorRestartNodeParams(name: 'sandbox'),
        disableConcurrentBuilds: true,
        // restart a random node every 30min
        // parameterizedCron: 'H/30 * * * * %RANDOM_NODE=true',
    ],
    [
        name: 'private/Deployments/sandbox/Protocol-Upgrade',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorProtocolUpgradeNetwork()'),
        env: [
            NET_NAME: 'sandbox',
            ANSIBLE_LIMIT: 'sandbox',
        ],
        parameters: vegavisorProtocolUpgradeParams(),
        disableConcurrentBuilds: true,
    ],
    //
    // Stagnet 1
    //
    [
        name: 'private/Deployments/stagnet1/Manage-Network',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNetwork()'),
        env: [
            NET_NAME: 'stagnet1',
            ANSIBLE_LIMIT: 'stagnet1',
        ],
        parameters: vegavisorRestartNetworkParams(),
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/stagnet1/Manage-Node',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNode()'),
        env: [
            NET_NAME: 'stagnet1',
        ],
        parameters: vegavisorRestartNodeParams(name: 'stagnet1'),
        disableConcurrentBuilds: true,
        // restart a random node every 30min
        //parameterizedCron: 'H/30 * * * * %RANDOM_NODE=true',
    ],
    [
        name: 'private/Deployments/stagnet1/Protocol-Upgrade',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorProtocolUpgradeNetwork()'),
        env: [
            NET_NAME: 'stagnet1',
            ANSIBLE_LIMIT: 'stagnet1',
        ],
        parameters: vegavisorProtocolUpgradeParams(),
        disableConcurrentBuilds: true,
    ],
    // fairground
    [
        name: 'private/Deployments/fairground/Manage-Network',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNetwork()'),
        env: [
            NET_NAME: 'fairground',
            ANSIBLE_LIMIT: 'fairground',
        ],
        parameters: vegavisorRestartNetworkParams('USE_CHECKPOINT': true),
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/fairground/Manage-Node',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorManageNode()'),
        env: [
            NET_NAME: 'fairground',
        ],
        parameters: vegavisorRestartNodeParams(name: 'testnet'),
        disableConcurrentBuilds: true,
        // restart a random node every 30min
        // parameterizedCron: 'H/30 * * * * %RANDOM_NODE=true',
    ],
    [
        name: 'private/Deployments/fairground/Protocol-Upgrade',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorProtocolUpgradeNetwork()'),
        env: [
            NET_NAME: 'fairground',
            ANSIBLE_LIMIT: 'fairground',
        ],
        parameters: vegavisorProtocolUpgradeParams(),
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/fairground/Topup-Bots',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorTopupBots()'),
        env: [
            NET_NAME: 'fairground',
        ],
        parameters: {
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
            stringParam('DEVOPSTOOLS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopstools repository')
        },
        cron: 'H/30 * * * *',
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/devnet1/Topup-Bots',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorTopupBots()'),
        env: [
            NET_NAME: 'devnet1',
        ],
        parameters: {
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
            stringParam('DEVOPSTOOLS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopstools repository')
        },
        cron: 'H/15 * * * *',
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/stagnet1/Topup-Bots',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegavisorTopupBots()'),
        env: [
            NET_NAME: 'stagnet1',
        ],
        parameters: {
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
            stringParam('DEVOPSTOOLS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopstools repository')
        },
        // cron: 'H/30 * * * *',
        disableConcurrentBuilds: true,
    ],
    // system-tests
    [
        name: 'common/system-tests-wrapper',
        useScmDefinition: false,
        definition: libDefinition('capsuleSystemTests()'),
        parameters: systemTestsParamsWrapper(),
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'common/system-tests-lnl-mainnet',
        useScmDefinition: false,
        definition: libDefinition('pipelineCapsuleLNL()'),
        parameters: lnlSystemTestsparams(),
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'common/system-tests',
        description: 'This job is just a functional wrapper over techincal call of old system-tests job. If you wish to trigger specific system-tests run go to https://jenkins.ops.vega.xyz/job/common/job/system-tests-wrapper/',
        useScmDefinition: false,
        definition: libDefinition('pipelineCapsuleSystemTests()'),
        parameters: systemTestsParamsGeneric('SCENARIO': 'PR'),
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'common/system-tests-nightly',
        description: 'This job is executed every 24h to ensure stability of the system',
        useScmDefinition: false,
        definition: libDefinition('pipelineCapsuleSystemTests()'),
        parameters: systemTestsParamsGeneric('SCENARIO': 'NIGHTLY'),
        copyArtifacts: true,
        daysToKeep: 14,
        cron: 'H 0 * * *',
    ],
    [
        name: 'common/vega-market-sim',
        description: 'Simulate Markets on fully controllable Simulator of Vega Network',
        useScmDefinition: false,
        definition: libDefinition('pipelineVegaMarketSim()'),
        parameters: {
            stringParam('ORIGIN_REPO', 'vegaprotocol/vega', 'repository which acts as vega source code (used for forks builds)')
            stringParam('VEGA_VERSION', 'develop', 'Git branch, tag or hash of the vegaprotocol/vega repository')
            stringParam('VEGA_MARKET_SIM_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/vega-market-sim repository')
            stringParam('TIMEOUT', '45', 'Number of minutes after which the job will stop')
            booleanParam('RUN_EXTRA_TESTS', false, 'Run extra tests that you don\'t always want to run')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'private/Snapshots/Devnet1',
        useScmDefinition: false,
        env: [
            NET_NAME: 'devnet1',
            DISABLE_TENDERMINT: 'true'
        ],
        parameters: {
            stringParam('TIMEOUT', '10', 'Number of minutes after which the node will stop')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        definition: libDefinition('pipelineSnapshotTesting()'),
        cron: "H/12 * * * *",
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Snapshots/Stagnet1',
        useScmDefinition: false,
        env: [
            NET_NAME: 'stagnet1',
            DISABLE_TENDERMINT: 'true'
        ],
        parameters: {
            stringParam('TIMEOUT', '10', 'Number of minutes after which the node will stop')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        definition: libDefinition('pipelineSnapshotTesting()'),
        cron: "H/12 * * * *",
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Snapshots/Fairground',
        disabled: true,
        useScmDefinition: false,
        env: [
            NET_NAME: 'fairground',
            DISABLE_TENDERMINT: 'true'
        ],
        parameters: {
            stringParam('TIMEOUT', '10', 'Number of minutes after which the node will stop')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        definition: libDefinition('pipelineSnapshotTesting()'),
        cron: "H/12 * * * *",
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Automations/Checkpoint-Backup',
        useScmDefinition: false,
        parameters: {
            booleanParam('DEVNET_1', false, 'Backup the latest checkpoint from the Devnet 1')
            booleanParam('FAIRGROUND', true, 'Backup the latest checkpoint from the Fairground network')
            booleanParam('MAINNET', true, 'Backup the latest checkpoint from the Mainnet')
            stringParam('CHECKPOINT_STORE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/checkpoint-store repository')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        //cron: 'H */2 * * *',
        disableConcurrentBuilds: true,
        description: 'Backup checkpoints from different networks into vegaprotocol/checkpoint-store',
        definition: libDefinition('pipelineCheckpointBackup()'),
    ],
    [
        name: 'private/Automations/BotsTopupStagnet3',
        useScmDefinition: false,
        parameters: {
            booleanParam('REMOVE_BOT_WALLETS', false, 'Define if bot wallets should be removed on the run.')
            stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        env: [
            NETWORK: 'stagnet3',
            CHECK_NETWORK_STATUS: false,
        ],
        cron: 'H */2 * * *',
        disableConcurrentBuilds: true,
        description: 'Top-Up bots on the Stagnet3 network. Runs every 4 hours.',
        definition: libDefinition('pipelineTopUpBots()'),
    ],
    // approbations
    [
        name: 'common/approbation',
        useScmDefinition: false,
        definition: libDefinition('pipelineApprobation(type: "core")'),
        parameters: approbationParams(type: 'core'),
        copyArtifacts: true,
    ],
    [
        name: 'common/approbation-frontend',
        useScmDefinition: false,
        definition: libDefinition('pipelineApprobation(type: "frontend")'),
        parameters: approbationParams(type: 'frontend'),
        copyArtifacts: true,
    ],
    [
        name: 'common/frontend-monorepo',
        repo: 'frontend-monorepo',
        jenkinsfile: 'Jenkinsfile',
        branch: 'develop',
        disableConcurrentBuilds: true,
    ],
    [
        name: 'common/vegawallet-desktop',
        repo: 'vegawallet-desktop',
        jenkinsfile: 'Jenkinsfile',
        branch: 'develop',
        disableConcurrentBuilds: true,
    ],
]

// MAIN
jobs.each { job ->
    createCommonPipeline(job)
}
