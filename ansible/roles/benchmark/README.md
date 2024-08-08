# Ansible Role `benchmark`

Ansible role for installing and running keycloak-benchmark on remote machines.

Role assumes presence of host inventory file and a matching SSH key for "sudoer" access to the hosts.
The hosts are expected to be included in `benchmark` group.

## Parameters

See `defaults/main.yml` for default values.

### Installation
- `kcb_zip`: Path to the local `keycloak-benchmark-*.zip` file.
If not provided the role will attempt to download the file based on `kcb_zip_url`.
- `kcb_zip_url`: URL from which `keycloak-benchmark-*.zip` file will be downloaded.
If not provided it will default to GitHub release URL based on `kcb_version`.
- `kcb_version`: Version of `keycloak-benchmark-*.zip` file. Optional.
If `kcb_zip` or `kcb_zip_url` are provided it will be set from the filename.
- `skip_install`: Defaults to `no`. If the installation step is skipped
then `kcb_version` needs to be provided to the execution phase.

### Execution
- `kcb_params`: Parameters to be passed to `kcb.sh` script.
- `kcb_heap_size`: The value of `-Xmx` maximum heap size parameter for Gatling JVM.
- `kcb_clean_results`: Cleanup the results dir before running new simulation.

### Other
- `update_system_packages`: Whether to update the system packages. Defaults to `no`.
- `install_java`: Whether to install OpenJDK on the system. Defaults to `yes`.
- `java_version`: Version of OpenJDK to be installed. Defaults to `21`.


## Example Playbook

An example playbook `benchmark.yml` that applies the role to hosts in the `benchmark` group:
```
- hosts: benchmark
  roles: [benchmark]
```

## Run keycloak-benchmark

Run:
```
ansible-playbook -i benchmark_${USER}_${region}.yml benchmark.yml \
  -e "kcb_params='--scenario=VALUE --server-url=VALUE --concurrent-users=VALUE ... '"
```

Note the outer and inner quotes for the `kcb_params`.

### Workload per Node

Workload parameter provided inside `kcb_params` will be divided by the number
of hosts in the `benchmark` group.

In case of `--concurrent-users` the value must be divisible by the number of nodes,
otherwise the execution will end with an error.

In case of `--users-per-sec` workload parameter the value will be simply divided,
resulting in a floating-point value.

