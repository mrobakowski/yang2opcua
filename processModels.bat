@echo off

echo ==== root model ====
.\model_compiler\Bin\Debug\Opc.Ua.ModelCompiler.exe ^
    -d2 ".\build\models\rootModel.Model.xml" ^
    -cg ".\build\models\rootModel.Model.csv" ^
    -o2 ".\build\models\OUT" -console