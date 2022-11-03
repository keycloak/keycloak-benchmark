# Keycloak Community

Keycloak is an Open Source Identity and Access Management solution for modern Applications and Services. Keycloak Benchmark is the benchmarking tool supported by the Keycloak Community.

## Building and working with the codebase

Question: Not sure if we should provide more details about this section or have the users pointed to our documentation

## Contributing to keycloak-benchmark

This guide is heavily influenced by the Keycloak's own Contributing guidelines.

Here's a quick checklist for a good PR, more details below:

1. A GitHub Issue with a good description associated with the PR
2. One feature/change per PR
3. One commit per PR
4. PR rebased on main (`git rebase`, not `git pull`)
5. Good descriptive commit message, with a link to the issue
6. No changes to code not directly related to your PR
7. Updates the documentation where necessary

Once you have submitted your PR please monitor it for comments/feedback. We reserve the right to close inactive PRs if
you do not respond within 4 weeks (bear in mind you can always open a new PR if it is closed due to inactivity).

### Finding something to work on

If you would like to contribute to keycloak-benchmark, but are not sure exactly what to work on, you can find a number of open
issues that are awaiting contributions in
[issues](https://github.com/keycloak/keycloak-benchmark/issues).

### Create an issue

Take your time to write a proper issue including a good summary and description.

Remember this may be the first thing a reviewer of your PR will look at to get an idea of what you are proposing
and it will also be used by the community in the future to find about what new features and enhancements are included in
new releases.

### Implementing

Do not format or refactor code that is not directly related to your contribution. If you do this it will significantly
increase our effort in reviewing your PR. If you have a strong need to refactor code then submit a separate PR for the
refactoring.

### Testing

It would be great to test the changes locally and add a confirmation that it works would be a good show of faith to us. There are also some RightGithub Action based checks for certain components to test the PR.

### Documentation

We require contributions to include relevant documentation which is available on the [doc folder](https://github.com/keycloak/keycloak-benchmark/doc/) of the repository.

Alongside your code changes, also update the docs as part of the PR.

### Submitting your PR

When preparing your PR make sure you have a single commit and your branch is rebased on the main branch from the
project repository.

This means use the `git rebase` command and not `git pull` when integrating changes from main to your branch. See
[Git Documentation](https://git-scm.com/book/en/v2/Git-Branching-Rebasing) for more details.

We require that you squash to a single commit. You can do this with the `git rebase -i HEAD~X` command where X
is the number of commits you want to squash. See the [Git Documentation](https://git-scm.com/book/en/v2/Git-Tools-Rewriting-History)
for more details.

The above helps us review your PR and also makes it easier for us to maintain the repository.

We also require that the commit message includes a link to the issue ([linking a pull request to an issue](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue)).

### Commit messages and issue linking

The format for a commit message should look like:

```
A brief descriptive summary

Optionally, more details around how it was implemented

Closes #1234
```

The very last part of the commit message should be a link to the GitHub issue, when done correctly GitHub will automatically link the issue with the PR. There are 3 alternatives provided by GitHub here:

* Closes: Issues in the same repository
* Fixes: Issues in a different repository (this shouldn't be used, as issues should be created in the correct repository instead)
* Resolves: When multiple issues are resolved (this should be avoided)

Although GitHub allows alternatives (close, closed, fix, fixed), please only use the above formats.

Creating multi line commit messages with `git` can be done with:

```
git commit -m "Summary" -m "Optional description" -m "Closes #1234"
```

Alternatively, `shift + enter` can be used to add line breaks:

```
$ git commit -m "Summary
>
> Optional description
>
> Closes #1234"
```

For more information linking PRs to issues refer to the [GitHub Documentation](https://docs.github.com/en/issues/tracking-your-work-with-issues/linking-a-pull-request-to-an-issue).
