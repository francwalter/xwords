/* -*- compile-command: "find-and-gradle.sh inXw4dDeb"; -*- */
/*
 * Copyright 2022 by Eric House (xwords@eehouse.org).  All rights reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 */

package org.eehouse.android.xw4;


import android.content.Context;


class Variants {
    private static final String TAG = Variants.class.getSimpleName();
    private static final String KEY_LASTVAR = TAG + "/lastvar";

    public static void checkUpdate( Context context )
    {
        String curName = BuildConfig.VARIANT_NAME;
        String prevName = DBUtils.getStringFor( context, KEY_LASTVAR, null );
        if ( null == prevName || !prevName.equals(curName)) {
            DBUtils.setStringFor( context, KEY_LASTVAR, curName );
            if ( null != prevName ) {
                onNewVariant( context, prevName );
            }
        }
    }


    // This is the place to adjust for what's different about two
    // variants. For example, I don't get asked for SMS permission when I
    // install a variant that allows SMS on top of the Google Play variant
    // that doesn't. Fix here PENDING
    private static void onNewVariant( Context context, String prevName  )
    {
        Log.d( TAG, "prev variant: %s; new variant: %s", prevName,
               BuildConfig.VARIANT_NAME );
    }
}
