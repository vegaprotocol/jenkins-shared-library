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
    'smoke API': [
        pytestDirectory: "tests/API",
        mark: 'smoke'
      ],
      'smoke AssetMarketData': [
        pytestDirectory: "tests/AssetMarketData",
        mark: 'smoke'
      ],
      'smoke assets': [
        pytestDirectory: "tests/assets",
        mark: 'smoke'
      ],
      'smoke cash_settled_futures': [
        pytestDirectory: "tests/cash_settled_futures",
        mark: 'smoke'
      ],
      'smoke collateral': [
        pytestDirectory: "tests/collateral",
        mark: 'smoke'
      ],
      'smoke data_sources': [
        pytestDirectory: "tests/data_sources",
        mark: 'smoke'
      ],
            'smoke datanode': [
        pytestDirectory: "tests/datanode",
        mark: 'smoke'
      ],
            'smoke deposits_withdrawals': [
        pytestDirectory: "tests/deposits_withdrawals",
        mark: 'smoke'
      ],
            'smoke fees': [
        pytestDirectory: "tests/fees",
        mark: 'smoke'
      ],
            'smoke gas': [
        pytestDirectory: "tests/gas",
        mark: 'smoke'
      ],
            'smoke governance': [
        pytestDirectory: "tests/governance",
        mark: 'smoke'
      ],
            'smoke limits': [
        pytestDirectory: "tests/limits",
        mark: 'smoke'
      ],
                  'smoke margins': [
        pytestDirectory: "tests/margins",
        mark: 'smoke'
      ],
                  'smoke market_data': [
        pytestDirectory: "tests/market_data",
        mark: 'smoke'
      ],
                  'smoke market_depth': [
        pytestDirectory: "tests/market_depth",
        mark: 'smoke'
      ],
                  'smoke matching_engine': [
        pytestDirectory: "tests/matching_engine",
        mark: 'smoke'
      ],
                  'smoke network_parameters': [
        pytestDirectory: "tests/network_parameters",
        mark: 'smoke'
      ],
                  'smoke orders': [
        pytestDirectory: "tests/orders",
        mark: 'smoke'
      ],
                  'smoke parties': [
        pytestDirectory: "tests/parties",
        mark: 'smoke'
      ],
                        'smoke price_monitoring': [
        pytestDirectory: "tests/price_monitoring",
        mark: 'smoke'
      ],
                        'smoke rewards': [
        pytestDirectory: "tests/rewards",
        mark: 'smoke'
      ],
                        'smoke settlement': [
        pytestDirectory: "tests/settlement",
        mark: 'smoke'
      ],
                        'smoke snapshots': [
        pytestDirectory: "tests/snapshots",
        mark: 'smoke'
      ],
                        'smoke spam': [
        pytestDirectory: "tests/spam",
        mark: 'smoke'
      ],
                              'smoke staking_delegation': [
        pytestDirectory: "tests/staking_delegation",
        mark: 'smoke'
      ],
                              'smoke trading': [
        pytestDirectory: "tests/trading",
        mark: 'smoke'
      ],

                              'smoke treasury': [
        pytestDirectory: "tests/treasury",
        mark: 'smoke'
      ],
                              'smoke TxfersAndMargins': [
        pytestDirectory: "tests/TxfersAndMargins",
        mark: 'smoke'
      ],
                              'smoke validators': [
        pytestDirectory: "tests/validators",
        mark: 'smoke'
      ],
                              'smoke wallet': [
        pytestDirectory: "tests/wallet",
        mark: 'smoke'
      ],
      'smoke newLnl': [
        pytestDirectory: "tests/newLNL",
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
                  RunWrapper downstreamBuild = build(
                    job: downstreamBuildName,
                    parameters: childParams,
                    propagate: false,  // don't fail yet
                  )
                  if (params.SCENARIO == 'NIGHTLY') {
                    build (
                      job: 'common/snapshot-soak-tests',
                      parameters: [
                        string(name: 'SYSTEM_TEST_JOB_NAME', value: downstreamBuildName),
                        string(name: 'SYSTEM_TEST_BUILD_NUMBER', value: downstreamBuild.getNumber() as String),
                        string(name: 'SUIT_NAME', value: name),
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
