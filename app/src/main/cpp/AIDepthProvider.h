#ifndef _AI_DEPTH_PROVIDER_H_
#define _AI_DEPTH_PROVIDER_H_

#include "IDepthProvider.h"
#include <mutex>
#include <vector>

class AIDepthProvider : public IDepthProvider {
public:
    AIDepthProvider();
    ~AIDepthProvider() override;

    void init(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memoryProperties, uint32_t queueFamilyIndex) override;
    void updateData(uint8_t *data, size_t width, size_t height) override;
    bool updateTexture() override;
    VulkanTexture* getTexture() override;
    void destroy() override;

private:
    VkDevice m_device;
    VkQueue m_queue;
    VkPhysicalDeviceMemoryProperties m_memoryProperties;
    uint32_t m_queueFamilyIndex;

    VulkanTexture m_texture{};
    bool m_isInitialized = false;
    std::mutex m_mutex;

    std::vector<uint8_t> m_stagingBuffer;
    size_t m_stagingWidth = 0;
    size_t m_stagingHeight = 0;
    bool m_hasNewData = false;

    VkResult allocateMemoryTypeFromProperties(uint32_t typeBits, VkFlags requirements_mask, uint32_t *typeIndex);
    void createTexture(size_t width, size_t height, VulkanTexture* texture);
    void deleteTexture(VulkanTexture* texture);
    void transitionImageLayout(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout);
};

#endif //_AI_DEPTH_PROVIDER_H_
