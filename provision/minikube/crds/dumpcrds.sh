#!/usr/bin/env bash

for i in $(kubectl get crd -o jsonpath='{.items[*].metadata.name}')
do
  kubectl get crd/$i -o yaml > $i.yaml
done

