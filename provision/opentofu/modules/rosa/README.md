# ROSA OpenTofu Modules
In order to use the modules in this directory, it's necessary for the Red Hat Cloud Services provider token to be set
via the `rhcs_token` variable. The value of this var should be an OpenShift Cluster Manager token, which can be accessed
in the [Red Hat Hybrid Cloud Console](https://console.redhat.com/openshift/token).

Instead of providing the token as a variable everytime `tofu` is called, it's possible to set the value via the
`TF_VAR_rhcs_token` environment variable.
