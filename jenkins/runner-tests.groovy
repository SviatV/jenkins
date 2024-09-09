import groovy.json.JsonSlurperClassic

timestamp {
    node("maven") {
        wrap([$class: 'BuildUser']) {
            currentBuild.description = """
User: ${BUILD_USER}
Branch: ${REFSPEC}
"""

            config = readYaml text: env.YAML_CONFIG
            BUILD_USER_EMAIL = $BUILD_USER_EMAIL  // Unused, but kept

            if(config != null) {
                for(param in config.entrySet()) {
                    env.setProperty(param.getKey(), param.getValue())
                }
            }

            testTypes = env.getProperty('TEST_TYPES').replace("[", "").replace("]", "").split(",\\s*")
        }

        def jobs = [:]
        def triggeredJobs = [:]

        for (type in testTypes) {
            // Corrected putAt warnings
            jobs.put(type, {
                node("maven") {
                    stage("Running $type tests") {
                        // Corrected putAt warning
                        triggeredJobs.put(type, build(job: "$type-tests", parameters: [
                                text(name: "YAML_CONFIG", value: env.YAML_CONFIG)
                        ]))
                    }
                }
            })
        }

        parallel jobs

        stage("Create Allure additional report artifacts") {
            dir("allure-results") {
                sh "echo BROWSER=${env.getProperty('BROWSER')} > environment.txt"
                sh "echo TEST_VERSION=${env.getProperty('TEST_VERSION')} >> environment.txt"
            }
        }

        stage("Copy allure reports") {
            dir("allure-results") {
                for(type in testTypes) {
                    // Fixed getAt warning
                    copyArtifacts filter: "allure-report.zip", projectName: triggeredJobs.get(type)?.projectName, selector: lastSuccessful(), optional: true
                    sh "unzip ./allure-report.zip -d ."
                    sh "rm -rf ./allure-report.zip"
                }
            }
        }

        stage("Publish Allure report") {
            dir("allure-results") {
                allure([
                        results: ["."],
                        reportBuildPolicy: ALWAYS
                ])
            }
        }

        stage("Send notification") {
            def message = "❕Test Results❕"
            // Fixed join warning
            message += "Tests running: ${String.join(', ', jobs.keySet().toList())}"
            message += "BRANCH: ${BRANCH}\n"

            def slurper = new JsonSlurperClassic().parseText(new File("./allure-results/widgets/summary.json").text)
            if (slurper['failed'] > 0) {
                message += 'Status: FAILED❌'
                message += "\\n @$BUILD_USER_EMAIL"
            } else if ((slurper['skipped'] as Integer) > 0 && (slurper['total'] as Integer) == 0) {
                message += 'Status: SKIPPED😱'
            } else {
                message += 'Status: PASSED✅'
            }

            withCredentials([string(credentialsId: 'telegram_token', valueVariable: "TELEGRAM_TOKEN")]) {
                // Fixed GString to String and method call ambiguity
                def url = "https://api.telegram.org/bot${TELEGRAM_TOKEN}/" as String
                def urlConnection = new URL(url).openConnection() as HttpURLConnection
                urlConnection.setRequestMethod('GET')
                urlConnection.setDoOutput(true)
            }
        }
    }
}
