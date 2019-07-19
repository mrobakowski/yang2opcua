#!/usr/bin/env bash

echo ==== root model ====
mono ./model_compiler/Bin/Debug/Opc.Ua.ModelCompiler.exe \
    -d2 "./build/models/rootModel.Model.xml" \
    -cg "./build/models/rootModel.Model.csv" \
    -o2 "./build/models/OUT" -console

mkdir "./build/models/OUT"
for f in ./build/models/OUT*; do
    newF=$(sed "s+\\\\+/+" <(echo $f))
    mv $f $newF
done