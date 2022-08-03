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
            daysToKeep(45)
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
            properties {
                pipelineTriggers {
                    triggers {
                        githubPush()
                    }
                }
                if (args.copyArtifacts) {
                    copyArtifactsPermission {
                        projectNames('*')
                    }
                }
            }
        }
        else {
            definition args.definition
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

veganetParams = {
    stringParam('VEGA_VERSION', '', "Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core. If you decide not to build binary by yourself you need to set version according to the versions available on releases page: https://github.com/vegaprotocol/vega/releases")
    booleanParam('DEPLOY_CONFIG', true, 'Deploy some Vega Network config, e.g. genesis file')
    booleanParam('BUILD_VEGA_CORE', false, 'Decide if VEGA_VERSION is to be build or downloaded')
    choiceParam('RESTART', ['YES_FROM_CHECKPOINT', 'YES', 'NO'], 'Restart the Network')
    booleanParam('CREATE_MARKETS', true, 'Create markets')
    booleanParam('CREATE_INCENTIVE_MARKETS', false, 'Create Markets for Incentive')
    booleanParam('BOUNCE_BOTS', true, 'Start & Top up liqbot and traderbot with fake/ERC20 tokens')
    booleanParam('REMOVE_WALLETS', false, 'Remove bot wallets on top up')
    stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
    stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
    stringParam('ANSIBLE_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/ansible repository')
    stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
}

systemTestsParams = {
    stringParam('VEGA_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/vega repository')
    stringParam('SYSTEM_TESTS_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/system-tests repository')
    stringParam('VEGACAPSULE_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/vegacapsule repository')
    stringParam('VEGATOOLS_BRANCH', 'develop', 'Git branch, tag or hash of the vegaprotocol/vegatools repository')
    stringParam('DEVOPS_INFRA_BRANCH', 'master', 'Git branch, tag or hash of the vegaprotocol/devops-infra repository')
    stringParam('DEVOPSSCRIPTS_BRANCH', 'main', 'Git branch, tag or hash of the vegaprotocol/devopsscripts repository')
    stringParam('SYSTEM_TESTS_TEST_FUNCTION', '', 'Run only a tests with a specified function name. This is actually a "pytest -k $SYSTEM_TESTS_TEST_FUNCTION_NAME" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
    stringParam('SYSTEM_TESTS_TEST_MARK', 'smoke', 'Run only a tests with the specified mark(s). This is actually a "pytest -m $SYSTEM_TESTS_TEST_MARK" command-line argument, see more: https://docs.pytest.org/en/stable/usage.html')
    stringParam('SYSTEM_TESTS_TEST_DIRECTORY', '', 'Run tests from files in this directory and all sub-directories')
    stringParam('CAPSULE_CONFIG', 'capsule_config.hcl', 'Run tests using the given vegacapsule config file')
    booleanParam('SYSTEM_TESTS_DEBUG', false, 'Enable debug logs for system-tests execution')
    stringParam('TIMEOUT', '300', 'Timeout in minutes, after which the pipline is force stopped.')
    booleanParam('PRINT_NETWORK_LOGS', false, 'By default logs are only archived as as Jenkins Pipeline artifact. If this is checked, the logs will be printed in jenkins as well')
    stringParam('JENKINS_SHARED_LIB_BRANCH', 'main', 'Branch of jenkins-shared-library from which pipeline should be run')
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
    ],

    // DSL Job - the one that manages this file
    [
        name: 'private/DSL Job',
        repo: 'jenkins-shared-library',
        description: h('this job is used to generate other jobs'),
        jenkinsfile: 'dsl/Jenkinsfile',
        branch: 'main',
    ],
    // Jenkins Configuration As Code
    [
        name: 'private/Jenkins Configuration as Code Pipeline',
        repo: 'jenkins-shared-library',
        description: h('This job is used to auto apply changes to jenkins instance configuration'),
        jenkinsfile: 'jcasc/Jenkinsfile',
        branch: 'main',
    ],
    [
        name: 'private/Deployments/Veganet/Devnet',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'devnet',
            DNS_ALIAS: 'd',
        ],
        // overwrites
        parameters: veganetParams << {
            stringParam('VEGA_VERSION', 'develop', "Git branch, tag or hash of the vegaprotocol/vega repository. Leave empty to not deploy a new version of vega core. If you decide not to build binary by yourself you need to set version according to the versions available on releases page: https://github.com/vegaprotocol/vega/releases")
            booleanParam('BUILD_VEGA_CORE', true, 'Decide if VEGA_VERSION is to be build or downloaded')
            choiceParam('RESTART', ['YES', 'NO'], 'Restart the Network') // do not support checkpoints for devnet
        },
    ],
    [
        name: 'private/Deployments/Veganet/Stagnet',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'stagnet',
        ],
        parameters: veganetParams,
    ],
    [
        name: 'private/Deployments/Veganet/Stagnet 2',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'stagnet2',
        ],
        parameters: veganetParams,
    ],
    [
        name: 'private/Deployments/Veganet/Fairground',
        useScmDefinition: false,
        definition: libDefinition('pipelineDeploy()'),
        env: [
            NET_NAME: 'testnet',
        ],
        parameters: veganetParams,
    ],
    // system-tests
    [
        name: 'common/system-tests-wrapper',
        useScmDefinition: false,
        definition: libDefinition('capsuleSystemTests()'),
        parameters: systemTestsParams,
        copyArtifacts: true,
    ],
    [
        name: 'common/system-tests',
        useScmDefinition: false,
        definition: libDefinition('pipelineCapsuleSystemTests()'),
        parameters: systemTestsParams,
        copyArtifacts: true,
    ],
]

// MAIN
jobs.each { job ->
    createCommonPipeline(job)
}