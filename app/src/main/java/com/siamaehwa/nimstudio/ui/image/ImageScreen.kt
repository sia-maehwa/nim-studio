package com.siamaehwa.nimstudio.ui.image

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ScreenHeader

@Composable
fun ImageScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "이미지 생성", subtitle = "M2에서 추가됩니다")
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyHint(
                icon = Icons.Filled.Image,
                title = "준비 중인 기능",
                body = "모델별 파라미터(크기·스텝·시드), 결과 갤러리 저장, 라이브러리 연동이 M2에서 추가됩니다.",
            )
        }
    }
}
