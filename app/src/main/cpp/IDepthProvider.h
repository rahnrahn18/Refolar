#ifndef _IDEPT_PROVIDER_H_
#define _IDEPT_PROVIDER_H_

#include "VKUtils.h"
#include <vulkan/vulkan.h>
#include <cstdint>

class IDepthProvider {
public:
    virtual ~IDepthProvider() = default;

    // Initialize with Vulkan device and memory properties
    virtual void init(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memoryProperties, uint32_t queueFamilyIndex) = 0;

    // Update the depth data (called from JNI)
    virtual void updateData(uint8_t *data, size_t width, size_t height) = 0;

    // Process/Upload texture (called from Render Thread)
    // Returns true if texture view changed (descriptor update needed)
    virtual bool updateTexture() = 0;

    // Get the current depth texture to bind in descriptor set
    virtual VulkanTexture* getTexture() = 0;

    // Clean up resources
    virtual void destroy() = 0;
};

#endif // _IDEPT_PROVIDER_H_
