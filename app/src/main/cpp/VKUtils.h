#ifndef _VK_UTILS_H_
#define _VK_UTILS_H_

#include <android/asset_manager_jni.h>
#include <vulkan/vulkan.h>

struct VulkanTexture {
    VkSampler sampler;
    VkImage image;
    VkImageLayout imageLayout;
    VkSubresourceLayout layout;
    VkDeviceMemory mem;
    VkImageView view;
    size_t width;
    size_t height;
    void *mapped;
};

bool createShaderModuleFromAsset(VkDevice device, const char *shaderFilePath,
                                 AAssetManager *assetManager, VkShaderModule *shaderModule);

#endif //_VK_UTILS_H_
