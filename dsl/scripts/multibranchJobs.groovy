
def h(def text, def num=4) {
    return "<h${num}>${text}</h${num}>"
}

def standardDescription(description = null) {
    def url = "https://github.com/vegaprotocol/jenkins-shared-library/tree/main/dsl"
    return h("""
        ${description ? "${description}<br/>" : ''}
        This job was automatically generated by DSL script located at <a href="${url}">this repository</a> and processed by <a href='${binding.variables.get('JOB_URL')}'>this job</a>, any manual configuration will be overriden.
    """, 5)
}

def generateUUIDForString(String name) {
    return UUID.nameUUIDFromBytes(name.getBytes()).toString();
}

// https://jenkins.ops.vega.xyz/plugin/job-dsl/api-viewer/index.html#path/multibranchPipelineJob
def createCommonMultibranchPipeline(Map args){
    return multibranchPipelineJob(args.name) {
        if (args.displayName) {
            displayName(args.displayName)
        }
        description(standardDescription(args.description))
        authorization {
            permission("hudson.model.Item.Read", "anonymous")
        }
        orphanedItemStrategy {
            discardOldItems {
                daysToKeep(14)
            }
        }
        triggers {
            periodicFolderTrigger {
                interval("1h")
            }
        }
        factory {
            workflowBranchProjectFactory {
                scriptPath("Jenkinsfile")
            }
        }
        branchSources {
            branchSource {
                source {
                    github {
                        repoOwner("vegaprotocol")
                        repository("${args.repoName ?: args.name}")
                        configuredByUrl(true)
                        repositoryUrl("https://github.com/vegaprotocol/${args.repoName ?: args.name}")
                        credentialsId('Vega Jenkins')
                        id(generateUUIDForString(args.name))
                        traits {
                            cloneOption {
                                extension {
                                    honorRefspec(true)
                                    noTags(false)
                                    timeout(3)
                                    shallow(false)
                                    reference("")
                                }
                            }
                            cleanAfterCheckout {
                                extension {
                                    deleteUntrackedNestedRepositories(true)
                                }
                            }
                            gitHubBranchDiscovery {
                                // 1 Exclude branches that are also filed as PRs
                                // 2 Only branches that are also filed as PRs
                                // 3 All branches
                                strategyId(3)
                            }
                            gitHubPullRequestDiscovery {
                                // 1 Merging the pull request with the current target branch revision
                                // 2 The current pull request revision
                                // 3 Both the current pull request revision and the pull request merged with the current target branch revision
                                strategyId(1)
                            }
                            gitHubTagDiscovery()
                        }
                    }
                }
                // https://stackoverflow.com/questions/55173365/how-to-disable-triggers-from-branch-indexing-but-still-allow-scm-triggering-in-m
                strategy {
                    allBranchesSame {
                        props {
                            suppressAutomaticTriggering {
                                strategy('INDEXING')
                                triggeredBranchesRegex('.*')
                            }
                        }
                    }
                }
                buildStrategies {
                    skipInitialBuildOnFirstBranchIndexing()
                    buildChangeRequests {
                        ignoreTargetOnlyChanges(true)
                        ignoreUntrustedChanges(true)
                    }
                    buildNamedBranches {
                        filters {
                            wildcards {
                                includes("develop main master")
                                caseSensitive(false)
                                excludes("")
                            }
                        }
                    }
                    buildTags {
                        atLeastDays("")
                        atMostDays("3")
                    }
                }
            }
        }
        // https://stackoverflow.com/questions/67871598/how-to-set-the-discovery-modes-for-multibranch-job-created-by-job-dsl
        if (args.discoverForks) {
            configure {
                def traits = it / 'sources' / 'data' / 'jenkins.branch.BranchSource' / 'source' / 'traits'
                traits << 'org.jenkinsci.plugins.github__branch__source.ForkPullRequestDiscoveryTrait' {
                    strategyId(2)
                    trust(class: 'org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait$TrustPermission')
                }
            }
        }
    }
}

def multibranchJobs = [
    [
        name: 'vegacapsule',
    ],
    [
        name: 'vega',
        discoverForks: true,
        description: 'A Go implementation of the Vega Protocol, a protocol for creating and trading derivatives on a fully decentralised network.',
        displayName: 'Vega Core',
    ],
    [
        name: 'private/system-tests',
        repoName: 'system-tests',
    ],
    [
        name: 'vega-market-sim',
    ],
]

multibranchJobs.each {
    createCommonMultibranchPipeline(it)
}
