发送端 (A) 的测量点：
T1_SEND_START - 用户点击发送按钮
T2_ENCRYPT_END - 加密完成的时刻,也就是开始上传到tap层（COS）的时刻
T3_UPLOAD_END - 上传完成的时刻(A收到上传成功响应的时候)

接收端 (B) 的测量点：
T4_POLL_DETECT - 轮询检测到新消息的时刻
T5_DOWNLOAD_END - 下载完成的时刻,也就是开始解密的时刻
T6_DISPLAY - 消息显示在UI上的时刻