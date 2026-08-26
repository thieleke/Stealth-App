package com.cosmos.unreddit.ui.common.widget

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.widget.ImageView
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import com.cosmos.unreddit.R

import com.google.android.material.card.MaterialCardView

class CardButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = com.google.android.material.R.attr.materialCardViewStyle
) : MaterialCardView(context, attrs, defStyleAttr) {

    private var icon: ImageView

    private var iconDrawable: Drawable? = null

    private val defaultCardColor: ColorStateList

    private val defaultIconColor: ColorStateList

    private var isInverted: Boolean = false

    init {
        context.theme.obtainStyledAttributes(
            attrs,
            R.styleable.CardButton,
            0, 0
        ).apply {
            try {
                val iconResId = getResourceId(R.styleable.CardButton_icon, -1)
                if (iconResId != -1) {
                    iconDrawable = AppCompatResources.getDrawable(context, iconResId)
                }
            } finally {
                recycle()
            }
        }

        inflate(context, R.layout.view_card_button, this)

        icon = findViewById(R.id.icon)

        defaultCardColor = cardBackgroundColor
        defaultIconColor = ImageViewCompat.getImageTintList(icon)
            ?: ColorStateList.valueOf(ContextCompat.getColor(context, R.color.colorPrimary))
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        icon.setImageDrawable(iconDrawable)
    }

    fun setIcon(@DrawableRes resId: Int) {
        setIcon(AppCompatResources.getDrawable(context, resId))
    }

    fun setIcon(drawable: Drawable?) {
        iconDrawable = drawable
        icon.setImageDrawable(drawable)
    }

    /**
     * Swaps the card and icon colors to signal that the option behind this button is active.
     */
    fun setInverted(inverted: Boolean) {
        if (isInverted == inverted) {
            return
        }
        isInverted = inverted

        if (inverted) {
            setCardBackgroundColor(defaultIconColor)
            ImageViewCompat.setImageTintList(icon, defaultCardColor)
        } else {
            setCardBackgroundColor(defaultCardColor)
            ImageViewCompat.setImageTintList(icon, defaultIconColor)
        }
    }
}
