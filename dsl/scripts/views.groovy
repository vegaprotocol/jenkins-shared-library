def createListView(Map args){
    listView(args.name) {
        recurse()
        jobs {
            args.jobs.each{ job ->
                name(job)
            }
            if (args.regex){
              regex(args.regex)
            }
        }
        if (args.description){
          description(args.description)
        }
        columns {
            status()
            weather()
            name()
            lastSuccess()
            lastFailure()
            lastDuration()
            buildButton()
            favoriteColumn()
        }
    }
}

createListView(
    name: 'Ansible jobs',
    regex: 'ansible.*',
    description: "View with all ansible jobs"
)

createListView(
    name: 'OPS',
    jobs: [
        'DSL Job'
    ],
    description: 'ops related jobs',
)
