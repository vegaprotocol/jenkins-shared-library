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


def standardDescription() {
    def url = "https://github.com/vegaprotocol/jenkins-shared-library/tree/main/dsl"
    return h("""
        This job was automatically generated by DSL script located at <a href="${url}">this repository</a> and processed by <a href='${binding.variables.get('JOB_URL')}'>this job</a>, any manual configuration will be overriden.
    """, 5)
}


def createCommonPipeline(args){
    args.repo = "git@github.com:vegaprotocol/${args.repo}.git"
    return pipelineJob(args.name) {
        def des = args.get('description', '')
        des += "${des ? '<br/>' : ''} ${standardDescription()}"
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

        if (args.get('useScmDefinition', true)) {
            properties {
                pipelineTriggers {
                    triggers {
                        githubPush()
                    }
                }
            }
        }
        if (args.copyArtifacts) {
            properties {
                copyArtifactPermission {
                    projectNames('*')
                }
            }
        }

        if (args.cron) {
            properties {
                pipelineTriggers {
                    triggers {
                        cron {
                            spec(args.cron)
                        }
                    }
                }
            }
        }
        if (args.disableConcurrentBuilds) {
            properties {
                disableConcurrentBuilds {
                    abortPrevious(args.abortPrevious ?: false)
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

capsuleParams = {
    booleanParam('BUILD_CAPSULE', true, h('decide if build vegacapsule from source if false VEGACAPSULE_VERSION will be looked up in releases page', 5))
    stringParam('VEGACAPSULE_VERSION', 'main', h('version of vegacapsule (tag, branch, any revision)'))
    stringParam('VEGA_VERSION', 'v0.52.0', h('version of vega core (tag)'))
    booleanParam('BUILD_VEGA_BINARY', false, h('determine whether vega binary is built or downloaded'))
    stringParam('DATA_NODE_VERSION', 'v0.52.0', h('version of data node (tag)'))
    booleanParam('BUILD_DATA_NODE_BINARY', false, h('determine whether data-node binary is built or downloaded'))
    choiceParam('ACTION', ['RESTART', 'START', 'STOP'], h('action to be performed with network'))
    booleanParam('REGENERATE_CONFIGS', false, h('check this to regenerate network configs with capsule', 5))
    booleanParam('UNSAFE_RESET_ALL', false, h('decide if vegacapsule should perform unsafe-reset-all on RESTART action', 5))
    stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
}

veganetParamsBase = {
    booleanParam('DEPLOY_CONFIG', true, 'Deploy some Vega Network config, e.g. genesis file')
    booleanParam('CREATE_MARKETS', true, 'Create markets')
    booleanParam('CREATE_INCENTIVE_MARKETS', false, 'Create Markets for Incentive')
    booleanParam('BOUNCE_BOTS', true, 'Start & Top up liqbot and traderbot with fake/ERC20 tokens')
    booleanParam('REMOVE_WALLETS', false, 'Remove bot wallets on top up')
    stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
    stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
    stringParam('ANSIBLE_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/ansible repository')
    stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
}

veganetParams = veganetParamsBase << {
    booleanParam('BUILD_VEGA_CORE', false, 'Decide if VEGA_VERSION is to be build or downloaded')
    stringParam('VEGA_VERSION', '', "Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core. If you decide not to build binary by yourself you need to set version according to the versions available on releases page: https://github.com/vegaprotocol/vega/releases")
    choiceParam('RESTART', ['YES_FROM_CHECKPOINT', 'YES', 'NO'], 'Restart the Network')
}

vegavisorParams = {
    stringParam('VEGA_VERSION', 'develop', "Git branch, tag or hash of the vegaprotocol/vega repository")
    booleanParam('REGENERATE_CONFIG', true, 'Should the Vega Network config be re-generated, e.g. genesis.json')

    stringParam('VEGACAPSULE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository')
    stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
    stringParam('ANSIBLE_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/ansible repository')
    stringParam('NETWORKS_INTERNAL_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/networks-internal repository')
    stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')

    booleanParam('CREATE_MARKETS', true, 'Create markets')
    booleanParam('CREATE_INCENTIVE_MARKETS', false, 'Create Markets for Incentive')
    booleanParam('BOUNCE_BOTS', true, 'Start & Top up liqbot and traderbot with fake/ERC20 tokens')
    booleanParam('REMOVE_WALLETS', false, 'Remove bot wallets on top up')
}

systemTestsParamsGeneric = {
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
}

systemTestsParamsWrapper = systemTestsParamsGeneric << {
    stringParam('SYSTEM_TESTS_TEST_FUNCTION', '', 'Run only a tests with a specified function name. This is actually a "pytest -k $SYSTEM_TESTS_TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
    stringParam('SYSTEM_TESTS_TEST_MARK', 'smoke', 'Run only a tests with the specified mark(s). This is actually a "pytest -m $SYSTEM_TESTS_TEST_MARK" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
    stringParam('SYSTEM_TESTS_TEST_DIRECTORY', '', 'Run tests from files in this directory and all sub-directories')
    stringParam('TEST_EXTRA_PYTEST_ARGS', '', 'extra args passed to system tests executiom')
    stringParam('CAPSULE_CONFIG', 'capsule_config.hcl', 'Run tests using the given vegacapsule config file')
}

def jobs = [
    // Capsule playground
    [
        name: 'private/Deployments/Vegacapsule/Stagnet 3',
        useScmDefinition: false,
        parameters: capsuleParams,
        definition: libDefinition('capsulePipelineWrapper()'),
        env: [
            NET_NAME: "stagnet3",
            S3_CONFIG_HOME: "s3://vegacapsule-test/stagnet3",
            NOMAD_ADDR: "https://n00.stagnet3.vega.xyz:4646",
        ],
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/Vegacapsule/Devnet 2',
        useScmDefinition: false,
        parameters: capsuleParams,
        definition: libDefinition('capsulePipelineWrapper()'),
        env: [
            NET_NAME: "devnet2",
            S3_CONFIG_HOME: "s3://vegacapsule-test/devnet2",
            NOMAD_ADDR: "https://n00.devnet.vega.xyz:4646",
        ],
        disableConcurrentBuilds: true,
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
        name: 'private/Deployments/Veganet/Devnet',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'devnet',
            DNS_ALIAS: 'd',
            DEPLOY_WALLET: 'true'
        ],
        // overwrites
        parameters: veganetParamsBase << {
            stringParam('VEGA_VERSION', 'develop', "Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core. If you decide not to build binary by yourself you need to set version according to the versions available on releases page: https://github.com/vegaprotocol/vega/releases")
            booleanParam('BUILD_VEGA_CORE', true, 'Decide if VEGA_VERSION is to be build or downloaded')
            choiceParam('RESTART', ['YES', 'NO'], 'Restart the Network') // do not support checkpoints for devnet
        },
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/Veganet/Stagnet',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'stagnet',
        ],
        parameters: veganetParams,
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/Veganet/Stagnet 2',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'stagnet2',
        ],
        parameters: veganetParams,
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/Veganet/Fairground',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'testnet',
        ],
        parameters: veganetParams,
        disableConcurrentBuilds: true,
    ],
    [
        name: 'private/Deployments/Vegavisor/Devnet 2',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeployVegavisor()'),
        env: [
            NET_NAME: 'devnet2',
        ],
        parameters: vegavisorParams,
        disableConcurrentBuilds: true,
    ],
    // system-tests
    [
        name: 'common/system-tests-wrapper',
        useScmDefinition: false,
        definition: libDefinition('capsuleSystemTests()'),
        parameters: systemTestsParamsWrapper,
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'common/system-tests',
        useScmDefinition: false,
        definition: libDefinition('capsuleSystemTests()'),
        // definition: libDefinition('pipelineCapsuleSystemTests()'),
        parameters: systemTestsParamsWrapper,
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    // this job spec is going to replace common/system-tests
    [
        name: 'common/system-tests-demo',
        description: 'This job is just a functional wrapper over techincal call of old system-tests job. If you wish to trigger specific system-tests run go to https://jenkins.ops.vega.xyz/job/common/job/system-tests-wrapper/',
        useScmDefinition: false,
        definition: libDefinition('pipelineCapsuleSystemTests()'),
        parameters: systemTestsParamsGeneric << {
            choiceParam('SCENARIO', ['PR', 'NIGHTLY'], 'Choose which scenario should be run, to see exact implementation of the scenario visit -> https://github.com/vegaprotocol/jenkins-shared-library/blob/main/vars/pipelineCapsuleSystemTests.groovy')
        },
        copyArtifacts: true,
        daysToKeep: 14,
    ],
    [
        name: 'private/Snapshots/Devnet',
        useScmDefinition: false,
        env: [
            NETWORK: 'devnet1',
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
            NETWORK: 'stagnet1',
        ],
        parameters: {
            stringParam('TIMEOUT', '10', 'Number of minutes after which the node will stop')
            stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
        },
        definition: libDefinition('pipelineSnapshotTesting()'),
        cron: "H/12 * * * *",
        disableConcurrentBuilds: true,
    ],

]

// MAIN
jobs.each { job ->
    createCommonPipeline(job)
}