import org.jenkinsci.plugins.workflow.support.steps.build.RunWrapper

void call() {
  if (currentBuild.upstreamBuilds) {
    RunWrapper upBuild = currentBuild.upstreamBuilds[0]
    currentBuild.displayName = "#${currentBuild.id} - ${upBuild.fullProjectName} #${upBuild.id}"
  }
  println('pipelineCapsuleSystemTests params: ' + params)
  def downstreamBuildName = 'common/system-tests-wrapper'
  // this is default scenario for smoke test, but it will require changing for other types
  scenario = [
    'PR': [
      'smoke a-g': [
        pytestDirectory: "tests/[a-gA-G]*",
        mark: 'smoke'
      ],
      'smoke h-m': [
        pytestDirectory: "tests/[h-mH-M]*",
        mark: 'smoke'
      ],
      'smoke n-z': [
        pytestArgs: "--ignore-glob 'tests/[a-mA-M]*'",
        mark: 'smoke'
      ],
      'network_infra_smoke a-z': [
        mark: 'network_infra_smoke',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
    ],
    'NIGHTLY': [
      'full a-f': [
        pytestDirectory: "tests/[a-fA-F]*",
        mark: 'full',
      ],
      'full g-n': [
        pytestDirectory: "tests/[g-nG-N]*",
        mark: 'full',
      ],
      'full o-r': [
        pytestDirectory: "tests/[o-rO-R]*",
        mark: 'full',
      ],
      'full s': [
        pytestDirectory: "tests/[sS]*",
        mark: 'full',
      ],
      'full t-z': [
        pytestArgs: "--ignore-glob 'tests/[a-sA-S]*'",
        mark: 'full',
      ],
      'network_infra a-o': [
        pytestDirectory: "tests/[a-oA-O]*",
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra p without validators': [
        pytestDirectory: "tests/[pP]*",
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra q-z without validators': [
        pytestArgs: "--ignore-glob 'tests/[a-pA-P]*' --ignore-glob 'tests/validators/*'",
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra validators a-p': [
        pytestDirectory: "tests/validators/[a-pA-P]*",
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
      'network_infra validators r-z': [
        pytestDirectory: "tests/validators/[r-zR-Z]*",
        mark: 'network_infra',
        capsuleConfig: 'capsule_config_network_infra.hcl'
      ],
    ]
  ][params.SCENARIO]

  pipeline {
    agent none
    options {
      timestamps()
      ansiColor('xterm')
      timeout(time: 270, unit: 'MINUTES')
    }
    stages {
      stage('config') {
        agent any
        steps {
          sh "printenv"
          echo "params=${params.inspect()}"
        }
      }
      stage('Call tests') {
        steps {
          script {
            if (scenario == null) {
              error('Invalid scenario. Please update the "SCENARIO" parameter. Selected the ' + params.SCENARIO)
            }

            parallel scenario.collectEntries { name, testSpec ->
              [
                (name): {
                  childParams = collectParams()
                  if (testSpec.pytestArgs) {
                    childParams += [string(name: 'TEST_EXTRA_PYTEST_ARGS', value: testSpec.pytestArgs)]
                  }
                  if (testSpec.pytestDirectory) {
                    childParams += [string(name: 'SYSTEM_TESTS_TEST_DIRECTORY', value: testSpec.pytestDirectory)]
                  }
                  if (testSpec.mark) {
                    childParams += [string(name: 'SYSTEM_TESTS_TEST_MARK', value: testSpec.mark)]
                  }
                  if (testSpec.capsuleConfig) {
                    childParams += [string(name: 'CAPSULE_CONFIG', value: testSpec.capsuleConfig)]
                  }
                  if (params.SCENARIO == 'NIGHTLY') {
                    childParams += [booleanParam(name: 'ARCHIVE_VEGA_BINARY', value: true)]
                  }
                  if (params.SCENARIO == 'NIGHTLY' && params.SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES == '') {
                    childParams += [stringParam(name: 'SYSTEM_TESTS_NETWORK_PARAM_OVERRIDES', value: '{"network.markPriceUpdateMaximumFrequency":"5s"}')]
                  }
                  RunWrapper downstreamBuild = build(
                    job: downstreamBuildName,
                    parameters: childParams,
                    propagate: false,  // don't fail yet
                    wait: true, 
                  )
                  if (params.SCENARIO == 'NIGHTLY') {
                    build (
                      job: 'common/snapshot-soak-tests',
                      parameters: [
                        string(name: 'SYSTEM_TEST_JOB_NAME', value: downstreamBuildName),
                        string(name: 'SYSTEM_TEST_BUILD_NUMBER', value: downstreamBuild.getNumber() as String),
                        string(name: 'SUIT_NAME', value: name),
                        string(name: 'JENKINS_SHARED_LIB_BRANCH', value: params.JENKINS_SHARED_LIB_BRANCH),
                      ],
                      propagate: true,
                      wait: true,
                    )
                  }
                  echo "System-Tests pipeline: ${downstreamBuild.absoluteUrl}"
                  node {
                    def targetDir = 'system-tests-' + name.replaceAll('[^A-Za-z0-9\\._]', '-')
                    // Copy all artifacts
                    copyArtifacts(
                        projectName: downstreamBuildName,
                        selector: specific("${downstreamBuild.number}"),
                        filter: "build/**",
                        fingerprintArtifacts: true,
                        target: targetDir
                    )
                    archiveArtifacts(
                      artifacts: "${targetDir}/**/*",
                      allowEmptyArchive: true
                    )
                    junit checksName: "System Tests results from ${name}",
                        testResults: "${targetDir}/build/test-reports/**/*",
                        skipPublishingChecks: false,
                        skipMarkingBuildUnstable: false
                  }
                  // Now fail if the downstream job failed
                  if (downstreamBuild.result == 'UNSTABLE') {
                      unstable("""UNSTABLE - Downstream 'System-Tests ${name}' pipeline failed.
                          Click for details: ${downstreamBuild.absoluteUrl}""")
                  } else if (downstreamBuild.result == 'ABORTED') {
                      currentBuild.result = 'ABORTED'
                      error("""ABORTED - Downstream 'System-Tests ${name}' pipeline failed.
                          Click for details: ${downstreamBuild.absoluteUrl}""")
                  } else if (downstreamBuild.result != 'SUCCESS') {
                      error("""${downstreamBuild.result} - Downstream 'System-Tests ${name}' pipeline failed.
                          Click for details: ${downstreamBuild.absoluteUrl}""")
                  }
                }
              ]
            }
          }
        }
      }
    }
  }
}
