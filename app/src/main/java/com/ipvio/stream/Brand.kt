package com.ipvio.stream

import androidx.compose.foundation.Image
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.Composable

/**
 * The IPVIO mark (play symbol with a gradient) as an image: res/drawable/ic_brand_mark.png.
 * The "IPVIO" lettering is set next to it as white text.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_brand_mark),
        contentDescription = null,
        modifier = modifier,
        contentScale = ContentScale.Fit
    )
}
