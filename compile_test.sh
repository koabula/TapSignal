#!/bin/bash

# 编译测试脚本
echo "开始编译测试..."

# 编译主要的修复文件
echo "编译SubAccountPoolManager..."
./gradlew :app:compilePlayProdDebugKotlin --no-daemon -x test 2>&1 | grep -E "(error|Error|ERROR|FAILED)" || echo "✅ 编译成功"

echo "编译测试完成"
