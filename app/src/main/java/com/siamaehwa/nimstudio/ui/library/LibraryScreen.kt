package com.siamaehwa.nimstudio.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.siamaehwa.nimstudio.ui.common.EmptyHint
import com.siamaehwa.nimstudio.ui.common.ScreenHeader

@Composable
fun LibraryScreen() {
    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(title = "라이브러리", subtitle = "M2에서 추가됩니다")
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            EmptyHint(
                icon = Icons.Filled.Folder,
                title = "준비 중인 기능",
                body = "생성한 이미지, 코드, 대화 내보내기를 한곳에서 찾고 검색·공유하는 라이브러리가 M2에서 추가됩니다.",
            )
        }
    }
}
