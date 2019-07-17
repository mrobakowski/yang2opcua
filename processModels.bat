@echo off

echo ==== ietf-yang-types ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\ietf-yang-types.Model.xml" ^
    -cg ".\build\models\ietf-yang-types.Model.csv" ^
    -o2 ".\build\models\OUT" -console

echo ==== ietf-interfaces ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\ietf-interfaces.Model.xml" ^
    -cg ".\build\models\ietf-interfaces.Model.csv" ^
    -o2 ".\build\models\OUT" -console

echo ==== iana-if-type ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\iana-if-type.Model.xml" ^
    -cg ".\build\models\iana-if-type.Model.csv" ^
    -o2 ".\build\models\OUT" -console

echo ==== ieee802-types ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\ieee802-types.Model.xml" ^
    -cg ".\build\models\ieee802-types.Model.csv" ^
    -o2 ".\build\models\OUT" -console

echo ==== ieee802-dot1q-types ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\ieee802-dot1q-types.Model.xml" ^
    -cg ".\build\models\ieee802-dot1q-types.Model.csv" ^
    -o2 ".\build\models\OUT" -console