#!/bin/bash
# Test is EFS is configured correctly, see
# * https://mobb.ninja/docs/rosa/aws-efs/
set -xeo pipefail

oc new-project efs-demo

cat <<EOF | oc apply -f -
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: pvc-efs-volume
spec:
  storageClassName: efs-sc
  accessModes:
    - ReadWriteMany
  resources:
    requests:
      storage: 5Gi
EOF

cat <<EOF | oc apply -f -
apiVersion: v1
kind: Pod
metadata:
 name: test-efs
spec:
  volumes:
   - name: efs-storage-vol
     persistentVolumeClaim:
       claimName: pvc-efs-volume
  containers:
   - name: test-efs
     image: centos:latest
     command: [ "/bin/bash", "-c", "--" ]
     args: [ "while true; do echo 'hello efs' | tee -a /mnt/efs-data/verify-efs && sleep 5; done;" ]
     volumeMounts:
       - mountPath: "/mnt/efs-data"
         name: efs-storage-vol
EOF

kubectl wait --for=condition=Ready --timeout=300s pod/test-efs

cat <<EOF | oc apply -f -
apiVersion: v1
kind: Pod
metadata:
 name: test-efs-read
spec:
  volumes:
   - name: efs-storage-vol
     persistentVolumeClaim:
       claimName: pvc-efs-volume
  containers:
   - name: test-efs-read
     image: centos:latest
     command: [ "/bin/bash", "-c", "--" ]
     args: [ "tail -f /mnt/efs-data/verify-efs" ]
     volumeMounts:
       - mountPath: "/mnt/efs-data"
         name: efs-storage-vol
EOF

kubectl wait --for=condition=Ready --timeout=300s pod/test-efs-read

if [[ "$(oc logs test-efs-read)" =~ .*"hello efs".* ]]; then
  echo It seems to work!
else
  echo "ERROR: Can't read data from shared volume"
  exit 1
fi

oc delete project efs-demo
