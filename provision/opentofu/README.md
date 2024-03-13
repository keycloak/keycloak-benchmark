# OpenTofu
This is the root directory for managing all OpenTofu state.

## Modules
All OpenTofu modules should be created in the `./modules` folder

## State
All root modules should use a [S3 Backend](https://opentofu.org/docs/language/settings/backends/s3/) to
store state so that it's possible for centralised management of resources created by the team. A root module should add
the following to their `providers.tf` file:

```terraform
terraform {
  backend "s3" {
    bucket         = "kcb-tf-state"
    key            = <TODO>
    region         = "eu-west-1"
    encrypt        = true
    dynamodb_table = "app-state"
  }
}
```
The `key` field should be String that's unique to the module and relates to the module's functionality.

To pull remote state for a given module to your local machine, execute:

- `tofu state pull`

### Workspaces
To isolate state created by different team members, [OpenTofu Workspaces](https://opentofu.org/docs/cli/workspaces/)
should be used where appropriate. For example, if user specific ROSA clusters are required a dedicated workspace should
be created for the cluster and any resources deployed to it.

Workspace CRUD:

- `tofu workspace list`
- `tofu workspace new/delete/select <workspace-name>`

When an existing workspace is selected, you must then execute `tofu state pull` to ensure that you have the latest state
on your local machine.

### Cleaning up old resources
The `./reaper.sh <dir>` script calls `tofu destroy` for all workspaces associated with the module directory passed to
the script.
