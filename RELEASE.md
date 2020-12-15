# How to release

1) Make sure that everything needed is commited and pushed inside https://github.com/keycloak/keycloak-benchmark in the "main" branch. Make sure that your local "main" branch has same content.
```
    git checkout main    
    git log -n 1
```

The commit number should be the same as the last commit inside https://github.com/keycloak/keycloak-benchmark/tree/main

2) Try the "dry" run of the release:
```
    git checkout main
    mvn clean install
    mvn release:clean release:prepare -DdryRun=true
```

3) Once it is all good, then do a real release (not just dry run):

```
    mvn release:clean release:prepare
```

4) Check that commits in the https://github.com/keycloak/keycloak-benchmark/tree/main have your commits added (release commit and "next-version" commit) and also there is new tag under https://github.com/keycloak/keycloak-benchmark/tags

NOTE: There is likely no need to run `mvn release:perform` and deploy anything into the maven repository. As long as someone wants to have dependency on this dataset, we may need to add this.

5) Just clean now

```
    mvn release:clean
```

6) Go to your tab and build it locally (Replace with the actual tag version)

```
    git checkout keycloak-benchmark-parent-0.1
    mvn clean install
    git checkout main
```

Check that this file (corresponding to your released version) exists on your laptop: `<<YOUR_HOME>>/.m2/repository/org/keycloak/keycloak-benchmark-dataset/0.1/keycloak-benchmark-dataset-0.1.jar`


7) Go to the https://github.com/keycloak/keycloak-benchmark/tags and on the right next to your tag (3 dots), click on the "Create release"

Add the name (ideally same as the name of the tag) and add some description. Click on `Add files` and browse to the JAR dataset file from step 6

8) Click on `Publish the release`. The JAR file should be there and can be downloaded with HTTP by various parties.
