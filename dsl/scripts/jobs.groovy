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
            }
        }
        else {
            definition args.definition
        }

    }
}


def jobs = [
    // Capsule playground
    [
        name: 'private/cd/vegacapsule-stagnet3',
        useScmDefinition: false,
        parameters: {
            booleanParam('BUILD_CAPSULE', true, h('decide if build vegacapsule from source if false VEGACAPSULE_VERSION will be looked up in releases page', 5))
            stringParam('VEGACAPSULE_VERSION', 'develop', h('version of vegacapsule (tag, branch, any revision)'))
            stringParam('VEGA_VERSION', 'v0.52.0', h('version of vega core (tag)'))
            stringParam('DATA_NODE_VERSION', 'v0.52.0', h('version of data node (tag)'))
            choiceParam('ACTION', ['RESTART', 'START', 'STOP'], h('action to be performed with network'))
            booleanParam('REGENERATE_CONFIGS', false, h('check this to regenerate network configs with capsule', 5))
            booleanParam('UNSAFE_RESET_ALL', false, h('decide if vegacapsule should perform unsafe-reset-all on RESTART action', 5))
        },
        definition: {
            cps {
                script("""
                @Library('vega-shared-library@main') _
                capsulePipelineWrapper()
                """)
            }
        },
        env: [
            S3_CONFIG_HOME: "s3://vegacapsule-test/stagnet3",
            NOMAD_ADDR: "https://n00.stagnet3.vega.xyz:4646",
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
    ]
]

// MAIN
jobs.each { job ->
    createCommonPipeline(job)
}