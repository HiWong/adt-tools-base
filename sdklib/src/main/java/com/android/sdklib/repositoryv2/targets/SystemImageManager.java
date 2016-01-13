package com.android.sdklib.repositoryv2.targets;

import com.android.SdkConstants;
import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.repository.api.LocalPackage;
import com.android.repository.api.RepoManager;
import com.android.repository.impl.meta.TypeDetails;
import com.android.repository.io.FileOp;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.repository.local.PackageParserUtils;
import com.android.sdklib.repositoryv2.IdDisplay;
import com.android.sdklib.repositoryv2.meta.DetailsTypes;
import com.android.sdklib.repositoryv2.meta.SysImgFactory;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimap;
import com.google.common.collect.Table;

import java.io.File;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code SystemImageManager} finds {@link SystemImage}s in the sdk, using a {@link RepoManager}
 */
public class SystemImageManager {

    private final FileOp mFop;

    private final RepoManager mRepoManager;

    /**
     * Used to validate ABI types.
     */
    private final DetailsTypes.SysImgDetailsType mValidator;

    private static final String SYS_IMG_NAME = "system.img";

    /**
     * How far down the directory hierarchy we'll search for system images (starting from a
     * package root).
     */
    private static final int MAX_DEPTH = 4;

    /**
     * The known system images and their associated packages.
     */
    private Map<SystemImage, LocalPackage> mImageToPackage;

    /**
     * Map of tag, version, and vendor to set of system image, for convenient lookup.
     */
    private Table<IdDisplay, AndroidVersion, Multimap<IdDisplay, SystemImage>> mValuesToImage;

    /**
     * Create a new {@link SystemImageManager} using the given {@link RepoManager}.<br/> {@code
     * factory} is used to enable validation.
     */
    public SystemImageManager(@NonNull RepoManager mgr, @NonNull SysImgFactory factory,
            @NonNull FileOp fop) {
        mFop = fop;
        mRepoManager = mgr;
        mValidator = factory.createSysImgDetailsType();
    }

    /**
     * Gets all the {@link SystemImage}s.
     */
    @NonNull
    public Collection<SystemImage> getImages() {
        if (mImageToPackage == null) {
            init();
        }
        return mImageToPackage.keySet();
    }

    /**
     * Gets a map from all our {@link SystemImage}s to their containing {@link LocalPackage}s.
     */
    public Map<SystemImage, LocalPackage> getImageMap() {
        if (mImageToPackage == null) {
            init();
        }
        return mImageToPackage;
    }

    /**
     * Lookup all the {@link SystemImage} with the given property values.
     */
    @NonNull
    public Collection<SystemImage> lookup(@NonNull IdDisplay tag, @NonNull AndroidVersion version,
            @Nullable IdDisplay vendor) {
        if (mValuesToImage == null) {
            init();
        }
        Multimap<IdDisplay, SystemImage> m = mValuesToImage.get(tag, version);
        return m == null ? ImmutableList.<SystemImage>of() : m.get(vendor);
    }

    private void init() {
        Map<SystemImage, LocalPackage> images = buildImageMap();
        Table<IdDisplay, AndroidVersion, Multimap<IdDisplay, SystemImage>> valuesToImage =
                HashBasedTable.create();
        for (SystemImage img : images.keySet()) {
            IdDisplay vendor = img.getAddonVendor();
            IdDisplay tag = img.getTag();
            AndroidVersion version = img.getAndroidVersion();
            Multimap<IdDisplay, SystemImage> vendorImageMap = valuesToImage.get(tag, version);
            if (vendorImageMap == null) {
                vendorImageMap = HashMultimap.create();
                valuesToImage.put(tag, version, vendorImageMap);
            }
            vendorImageMap.put(vendor, img);
        }
        mValuesToImage = valuesToImage;
        mImageToPackage = images;
    }

    @NonNull
    private Map<SystemImage, LocalPackage> buildImageMap() {
        Map<SystemImage, LocalPackage> result = Maps.newHashMap();
        Map<AndroidVersion, File> platformSkins = Maps.newHashMap();
        Collection<? extends LocalPackage> packages =
                mRepoManager.getPackages().getLocalPackages().values();
        for (LocalPackage p : packages) {
            if (p.getTypeDetails() instanceof DetailsTypes.PlatformDetailsType) {
                File skinDir = new File(p.getLocation(), SdkConstants.FD_SKINS);
                if (mFop.exists(skinDir)) {
                    platformSkins.put(DetailsTypes.getAndroidVersion(
                      (DetailsTypes.PlatformDetailsType) p.getTypeDetails()), skinDir);
                }
            }
        }
        for (LocalPackage p : packages) {
            TypeDetails typeDetails = p.getTypeDetails();
            if (typeDetails instanceof DetailsTypes.SysImgDetailsType ||
                    typeDetails instanceof DetailsTypes.PlatformDetailsType ||
                    typeDetails instanceof DetailsTypes.AddonDetailsType) {
                collectImages(p.getLocation(), p, 0, platformSkins, result);
            }
        }
        return result;
    }

    private void collectImages(File dir, LocalPackage p, int depth,
            Map<AndroidVersion, File> platformSkins,
            Map<SystemImage, LocalPackage> collector) {
        for (File f : mFop.listFiles(dir)) {
            // Instead of just f.getName().equals, we first check f.getPath().endsWith,
            // because getPath() is a simpler getter whereas getName() computes a new
            // string on each call
            if (f.getPath().endsWith(SYS_IMG_NAME) && f.getName().equals(SYS_IMG_NAME)) {
                collector.put(createSysImg(p, dir, platformSkins), p);
            }
            if (depth < MAX_DEPTH && mFop.isDirectory(f)) {
                String name = f.getName();
                if (name.equals(SdkConstants.FD_DATA) ||
                       name.equals(SdkConstants.FD_SAMPLES) ||
                       name.equals(SdkConstants.FD_SKINS)) {
                    // Not containers for system images, but have a lot of files
                    continue;
                }
                collectImages(f, p, depth + 1, platformSkins, collector);
            }
        }
    }

    private SystemImage createSysImg(LocalPackage p, File dir,
            Map<AndroidVersion, File> platformSkins) {
        String containingDir = dir.getName();
        String abi;
        TypeDetails details = p.getTypeDetails();
        AndroidVersion version = null;
        if (details instanceof DetailsTypes.ApiDetailsType) {
            version = DetailsTypes.getAndroidVersion((DetailsTypes.ApiDetailsType) details);
        }
        if (details instanceof DetailsTypes.SysImgDetailsType) {
            abi = ((DetailsTypes.SysImgDetailsType) details).getAbi();
        } else if (mValidator.isValidAbi(containingDir)) {
            abi = containingDir;
        } else {
            abi = SdkConstants.ABI_ARMEABI;
        }

        IdDisplay tag;
        IdDisplay vendor = null;
        if (details instanceof DetailsTypes.AddonDetailsType) {
            vendor = ((DetailsTypes.AddonDetailsType) details).getVendor();
        } else if (details instanceof DetailsTypes.SysImgDetailsType) {
            vendor = ((DetailsTypes.SysImgDetailsType) details).getVendor();
        }

        if (details instanceof DetailsTypes.SysImgDetailsType) {
            tag = ((DetailsTypes.SysImgDetailsType) details).getTag();
        } else if (details instanceof DetailsTypes.AddonDetailsType) {
            tag = ((DetailsTypes.AddonDetailsType) details).getTag();
        } else {
            tag = SystemImage.DEFAULT_TAG;
        }

        File skinDir = new File(dir, SdkConstants.FD_SKINS);
        if (!mFop.exists(skinDir) && version != null) {
            skinDir = platformSkins.get(version);
        }
        File[] skins;
        if (skinDir != null) {
            List<File> skinList = PackageParserUtils.parseSkinFolder(skinDir, mFop);
            skins = skinList.toArray(new File[skinList.size()]);
        } else {
            skins = new File[0];
        }
        return new SystemImage(dir, tag, vendor, abi, skins, p);
    }
}