# Keycloak Benchmark Ansible

The Keycloak Benchmark Ansible module allows running Keycloak Benchmark on remote hosts.

## AWS EC2 Resources
- `roles/aws_ec2` - Ansible role.
- `roles/aws_ec2/defaults/main.yml` - Ansible role's defaults, can be overriden in `env.yml` which is picked up by the wrapper script.
- `aws_ec2.sh` - Wrapper script.

### 1. Install the required Ansible AWS collections
```
./aws_ec2.sh requirements
```

### 2. Create EC2 instances and related infrastructure
```
./aws_ec2.sh create <REGION>
```

This will create an Ansible host inventory file and a matching SSH private key to access the hosts.
- `benchmark_<USERNAME>_<REGION>_inventory.yml`
- `benchmark_<USERNAME>_<REGION>.pem`

The most important parameters to customize in `env.yml` are:
- `cluster_size`: Number of instances to be created.
- `instance_type`: Size of instances to be created, i.e. [AWS instance type](https://aws.amazon.com/ec2/instance-types/).

### 3. Stop / Start EC2 instances
```
./aws_ec2.sh stop <REGION>
./aws_ec2.sh start <REGION>
```

The `start` action will recreate the host inventory file with the restarted hosts' new IP addresses.

### 4. Delete EC2 instances and related resources
```
./aws_ec2.sh delete <REGION>
```

This will delete the instances and related resources, and the local inventory file and private key.


## Benchmark
- `roles/benchmark` - Ansible role.
- `roles/benchmark/defaults/main.yml` - Ansible role's defaults, can be overriden in `env.yml` which is picked up by the wrapper script.
- `benchmark.sh` - Wrapper script.

### Install and run the benchmark
```
./benchmark.sh <REGION> <P1> <P2> ... <Pn>
```

This will install Keycloak Benchmark on hosts listed in the `benchmark_<USERNAME>_<REGION>_inventory.yml`
and run the benchmark passing the parameters P1, P2, ... Pn, to the `kcb.sh` script.

Other parameters can be customized via `env.yml` file.

Version of benchmark to install can be specified via `kcb_version` parameter.

It is also possible to directly provide `kcb_zip` parameter if the file is already available locally, 
or the `kcb_zip_url` from which the benchmark will be downloaded. The `kcb_version` will then be extracted from the filename.

Parameter `skip_install` can be used to skip the installatin step.
In that case variable `kcb_version` must be provided based on what has been previously installed.


### Results

Aggregate report from the distributed simulation will be stored locally in:
```
files/
  benchmark/
    keycloak-benchmark-{{ kcb_version }}/
      results/
        {{ scenario }}-{{ timestamp }}/
          simulation/
            {{ host_1 }}.log
            {{ host_2 }}.log
            ...
          gatling/
            {{ host_1 }}.log
            {{ host_2 }}.log
            ...
            {{ host_1 }}.rc
            {{ host_2 }}.rc
            ...
          index.html
```

The `simulation/` directory contains simulation data from the individual nodes.

The `gatling/` directory contains gatling logs and return codes from the individual nodes.
