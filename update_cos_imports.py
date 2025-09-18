#!/usr/bin/env python3
"""
COS Provider迁移脚本

用于批量修改tap/provider/cos/cos和coscomm目录下所有Kotlin文件的包名依赖关系。

将:
- package org.thoughtcrime.securesms.cos -> package org.thoughtcrime.securesms.tap.provider.cos.cos
- package org.thoughtcrime.securesms.coscomm -> package org.thoughtcrime.securesms.tap.provider.cos.coscomm
- import org.thoughtcrime.securesms.cos -> import org.thoughtcrime.securesms.tap.provider.cos.cos
- import org.thoughtcrime.securesms.coscomm -> import org.thoughtcrime.securesms.tap.provider.cos.coscomm

作者: TAP迁移工具
"""

import os
import re
import sys
from pathlib import Path

def update_file_imports(file_path, dry_run=False):
    """
    更新单个文件的import和package语句
    
    Args:
        file_path: 文件路径
        dry_run: 是否为预览模式（不实际修改文件）
    
    Returns:
        tuple: (是否有修改, 修改列表)
    """
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            content = f.read()
        
        original_content = content
        changes = []
        
        # 1. 修改package声明
        # package org.thoughtcrime.securesms.cos -> package org.thoughtcrime.securesms.tap.provider.cos.cos
        cos_package_pattern = r'^package org\.thoughtcrime\.securesms\.cos(\s*$)'
        cos_package_replacement = r'package org.thoughtcrime.securesms.tap.provider.cos.cos\1'
        if re.search(cos_package_pattern, content, re.MULTILINE):
            content = re.sub(cos_package_pattern, cos_package_replacement, content, flags=re.MULTILINE)
            changes.append("package org.thoughtcrime.securesms.cos -> package org.thoughtcrime.securesms.tap.provider.cos.cos")
        
        # package org.thoughtcrime.securesms.coscomm -> package org.thoughtcrime.securesms.tap.provider.cos.coscomm
        coscomm_package_pattern = r'^package org\.thoughtcrime\.securesms\.coscomm([\.\w]*\s*$)'
        def coscomm_package_replacement(match):
            suffix = match.group(1)
            return f'package org.thoughtcrime.securesms.tap.provider.cos.coscomm{suffix}'
        
        if re.search(coscomm_package_pattern, content, re.MULTILINE):
            content = re.sub(coscomm_package_pattern, coscomm_package_replacement, content, flags=re.MULTILINE)
            changes.append("package org.thoughtcrime.securesms.coscomm.* -> package org.thoughtcrime.securesms.tap.provider.cos.coscomm.*")
        
        # 2. 修改import语句
        # import org.thoughtcrime.securesms.cos -> import org.thoughtcrime.securesms.tap.provider.cos.cos
        cos_import_pattern = r'^import org\.thoughtcrime\.securesms\.cos([\.\w\*]*\s*$)'
        def cos_import_replacement(match):
            suffix = match.group(1)
            return f'import org.thoughtcrime.securesms.tap.provider.cos.cos{suffix}'
        
        matches = list(re.finditer(cos_import_pattern, content, re.MULTILINE))
        if matches:
            content = re.sub(cos_import_pattern, cos_import_replacement, content, flags=re.MULTILINE)
            changes.append(f"修改了 {len(matches)} 个 cos import 语句")
        
        # import org.thoughtcrime.securesms.coscomm -> import org.thoughtcrime.securesms.tap.provider.cos.coscomm
        coscomm_import_pattern = r'^import org\.thoughtcrime\.securesms\.coscomm([\.\w\*]*\s*$)'
        def coscomm_import_replacement(match):
            suffix = match.group(1)
            return f'import org.thoughtcrime.securesms.tap.provider.cos.coscomm{suffix}'
        
        matches = list(re.finditer(coscomm_import_pattern, content, re.MULTILINE))
        if matches:
            content = re.sub(coscomm_import_pattern, coscomm_import_replacement, content, flags=re.MULTILINE)
            changes.append(f"修改了 {len(matches)} 个 coscomm import 语句")
        
        # 3. 修改代码中的类引用（如果有完全限定名）
        # org.thoughtcrime.securesms.cos. -> org.thoughtcrime.securesms.tap.provider.cos.cos.
        cos_ref_pattern = r'org\.thoughtcrime\.securesms\.cos\.'
        cos_ref_replacement = r'org.thoughtcrime.securesms.tap.provider.cos.cos.'
        matches = list(re.finditer(cos_ref_pattern, content))
        if matches:
            content = re.sub(cos_ref_pattern, cos_ref_replacement, content)
            changes.append(f"修改了 {len(matches)} 个 cos 类引用")
        
        # org.thoughtcrime.securesms.coscomm. -> org.thoughtcrime.securesms.tap.provider.cos.coscomm.
        coscomm_ref_pattern = r'org\.thoughtcrime\.securesms\.coscomm\.'
        coscomm_ref_replacement = r'org.thoughtcrime.securesms.tap.provider.cos.coscomm.'
        matches = list(re.finditer(coscomm_ref_pattern, content))
        if matches:
            content = re.sub(coscomm_ref_pattern, coscomm_ref_replacement, content)
            changes.append(f"修改了 {len(matches)} 个 coscomm 类引用")
        
        # 检查是否有变化
        has_changes = content != original_content
        
        # 如果不是预览模式且有变化，写入文件
        if not dry_run and has_changes:
            with open(file_path, 'w', encoding='utf-8') as f:
                f.write(content)
        
        return has_changes, changes
        
    except Exception as e:
        print(f"处理文件 {file_path} 时发生错误: {e}")
        return False, []

def process_directory(base_dir, dry_run=False):
    """
    处理目录下的所有Kotlin文件
    
    Args:
        base_dir: 基础目录路径
        dry_run: 是否为预览模式
    """
    base_path = Path(base_dir)
    if not base_path.exists():
        print(f"错误: 目录不存在: {base_dir}")
        return
    
    # 查找所有.kt文件
    kt_files = list(base_path.rglob("*.kt"))
    
    if not kt_files:
        print(f"在 {base_dir} 目录下未找到Kotlin文件")
        return
    
    print(f"在 {base_dir} 目录下找到 {len(kt_files)} 个Kotlin文件")
    
    if dry_run:
        print("\n=== 预览模式 - 不会实际修改文件 ===")
    else:
        print("\n=== 开始修改文件 ===")
    
    total_modified = 0
    total_files = len(kt_files)
    
    for i, kt_file in enumerate(kt_files, 1):
        print(f"\n[{i}/{total_files}] 处理: {kt_file.relative_to(base_path)}")
        
        has_changes, changes = update_file_imports(kt_file, dry_run)
        
        if has_changes:
            total_modified += 1
            print(f"  ✓ 有修改:")
            for change in changes:
                print(f"    - {change}")
        else:
            print(f"  - 无需修改")
    
    print(f"\n=== 处理完成 ===")
    print(f"总文件数: {total_files}")
    print(f"修改文件数: {total_modified}")
    print(f"无需修改: {total_files - total_modified}")

def main():
    """主函数"""
    import argparse
    
    parser = argparse.ArgumentParser(description='COS Provider包名迁移工具')
    parser.add_argument('directory', help='要处理的目录路径')
    parser.add_argument('--dry-run', action='store_true', help='预览模式，不实际修改文件')
    parser.add_argument('--recursive', action='store_true', default=True, help='递归处理子目录')
    
    args = parser.parse_args()
    
    if not os.path.exists(args.directory):
        print(f"错误: 目录不存在: {args.directory}")
        sys.exit(1)
    
    if args.dry_run:
        print("运行在预览模式，将显示需要的修改但不会实际修改文件")
        print("如需实际修改，请移除 --dry-run 参数")
    
    process_directory(args.directory, args.dry_run)

if __name__ == "__main__":
    main() 