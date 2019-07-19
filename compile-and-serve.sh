#!/usr/bin/env bash

cd opcua-server
mkdir build
cd build
cmake ..
make -j

./server