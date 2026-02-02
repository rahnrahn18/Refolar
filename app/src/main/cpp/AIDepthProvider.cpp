#include "AIDepthProvider.h"
#include <cstring>
#include <vector>
#include <cassert>
#include <android/log.h>

#define LOG_TAG "AIDepthProvider"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

AIDepthProvider::AIDepthProvider() {}

AIDepthProvider::~AIDepthProvider() {
    destroy();
}

void AIDepthProvider::init(VkDevice device, VkQueue queue, VkPhysicalDeviceMemoryProperties memoryProperties, uint32_t queueFamilyIndex) {
    m_device = device;
    m_queue = queue;
    m_memoryProperties = memoryProperties;
    m_queueFamilyIndex = queueFamilyIndex;

    // Create dummy 1x1 texture
    createTexture(1, 1, &m_texture);

    // Fill with white (1.0 = sharp) for fail-safe
    if (m_texture.mapped) {
        memset(m_texture.mapped, 255, 1);
    }

    m_isInitialized = true;
}

void AIDepthProvider::destroy() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (m_isInitialized) {
        deleteTexture(&m_texture);
        m_isInitialized = false;
    }
}

VulkanTexture* AIDepthProvider::getTexture() {
    return &m_texture;
}

void AIDepthProvider::updateData(uint8_t *data, size_t width, size_t height) {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isInitialized) return;

    size_t size = width * height;
    if (m_stagingBuffer.size() < size) {
        m_stagingBuffer.resize(size);
    }
    memcpy(m_stagingBuffer.data(), data, size);
    m_stagingWidth = width;
    m_stagingHeight = height;
    m_hasNewData = true;
}

bool AIDepthProvider::updateTexture() {
    std::lock_guard<std::mutex> lock(m_mutex);
    if (!m_isInitialized || !m_hasNewData) return false;

    bool textureRecreated = false;
    if (m_texture.width != m_stagingWidth || m_texture.height != m_stagingHeight) {
        deleteTexture(&m_texture);
        createTexture(m_stagingWidth, m_stagingHeight, &m_texture);
        textureRecreated = true;
    }

    if (m_texture.mapped) {
        uint8_t *dst = (uint8_t *) m_texture.mapped;
        uint8_t *src = m_stagingBuffer.data();
        for (size_t i = 0; i < m_texture.height; ++i) {
            memcpy(dst, src + i * m_texture.width, m_texture.width);
            dst += m_texture.layout.rowPitch;
        }
    }

    m_hasNewData = false;
    return textureRecreated;
}

void AIDepthProvider::createTexture(size_t width, size_t height, VulkanTexture* texture) {
    texture->width = width;
    texture->height = height;

    VkImageCreateInfo imageInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .imageType = VK_IMAGE_TYPE_2D,
        .format = VK_FORMAT_R8_UNORM,
        .extent = {(uint32_t)width, (uint32_t)height, 1},
        .mipLevels = 1,
        .arrayLayers = 1,
        .samples = VK_SAMPLE_COUNT_1_BIT,
        .tiling = VK_IMAGE_TILING_LINEAR,
        .usage = VK_IMAGE_USAGE_SAMPLED_BIT,
        .sharingMode = VK_SHARING_MODE_EXCLUSIVE,
        .queueFamilyIndexCount = 1,
        .pQueueFamilyIndices = &m_queueFamilyIndex,
        .initialLayout = VK_IMAGE_LAYOUT_PREINITIALIZED,
    };
    if (vkCreateImage(m_device, &imageInfo, nullptr, &texture->image) != VK_SUCCESS) {
        LOGE("failed to create image!");
    }

    VkMemoryRequirements memReqs;
    vkGetImageMemoryRequirements(m_device, texture->image, &memReqs);

    VkMemoryAllocateInfo allocInfo = {
        .sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO,
        .pNext = nullptr,
        .allocationSize = memReqs.size,
    };
    // Request Coherent memory to avoid needing explicit flushing
    VkResult res = allocateMemoryTypeFromProperties(memReqs.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
        &allocInfo.memoryTypeIndex);

    if (res != VK_SUCCESS) {
         // Fallback to non-coherent
         res = allocateMemoryTypeFromProperties(memReqs.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            &allocInfo.memoryTypeIndex);
    }

    if (vkAllocateMemory(m_device, &allocInfo, nullptr, &texture->mem) != VK_SUCCESS) {
        LOGE("failed to allocate image memory!");
    }
    vkBindImageMemory(m_device, texture->image, texture->mem, 0);

    // Map memory
    vkMapMemory(m_device, texture->mem, 0, memReqs.size, 0, &texture->mapped);

    // Get layout
    VkImageSubresource subres = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0};
    vkGetImageSubresourceLayout(m_device, texture->image, &subres, &texture->layout);

    // Transition to GENERAL layout
    transitionImageLayout(texture->image, VK_IMAGE_LAYOUT_PREINITIALIZED, VK_IMAGE_LAYOUT_GENERAL);
    texture->imageLayout = VK_IMAGE_LAYOUT_GENERAL;

    VkImageViewCreateInfo viewInfo = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .image = texture->image,
        .viewType = VK_IMAGE_VIEW_TYPE_2D,
        .format = VK_FORMAT_R8_UNORM,
        .components = {VK_COMPONENT_SWIZZLE_R, VK_COMPONENT_SWIZZLE_G, VK_COMPONENT_SWIZZLE_B, VK_COMPONENT_SWIZZLE_A},
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };
    if (vkCreateImageView(m_device, &viewInfo, nullptr, &texture->view) != VK_SUCCESS) {
        LOGE("failed to create image view!");
    }

    VkSamplerCreateInfo samplerInfo = {
        .sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO,
        .pNext = nullptr,
        .magFilter = VK_FILTER_LINEAR,
        .minFilter = VK_FILTER_LINEAR,
        .mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST,
        .addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
        .mipLodBias = 0.0f,
        .maxAnisotropy = 1,
        .compareOp = VK_COMPARE_OP_NEVER,
        .minLod = 0.0f,
        .maxLod = 0.0f,
        .borderColor = VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE,
        .unnormalizedCoordinates = VK_FALSE,
    };
    if (vkCreateSampler(m_device, &samplerInfo, nullptr, &texture->sampler) != VK_SUCCESS) {
        LOGE("failed to create sampler!");
    }
}

void AIDepthProvider::deleteTexture(VulkanTexture* texture) {
    if (texture->view) vkDestroyImageView(m_device, texture->view, nullptr);
    if (texture->sampler) vkDestroySampler(m_device, texture->sampler, nullptr);
    if (texture->image) vkDestroyImage(m_device, texture->image, nullptr);
    if (texture->mem) {
        vkUnmapMemory(m_device, texture->mem);
        vkFreeMemory(m_device, texture->mem, nullptr);
    }
    texture->mapped = nullptr;
    texture->view = VK_NULL_HANDLE;
    texture->sampler = VK_NULL_HANDLE;
    texture->image = VK_NULL_HANDLE;
    texture->mem = VK_NULL_HANDLE;
}

VkResult AIDepthProvider::allocateMemoryTypeFromProperties(uint32_t typeBits, VkFlags requirements_mask, uint32_t *typeIndex) {
    for (uint32_t i = 0; i < 32; i++) {
        if ((typeBits & 1) == 1) {
            if ((m_memoryProperties.memoryTypes[i].propertyFlags & requirements_mask) == requirements_mask) {
                *typeIndex = i;
                return VK_SUCCESS;
            }
        }
        typeBits >>= 1;
    }
    return VK_ERROR_MEMORY_MAP_FAILED;
}

void AIDepthProvider::transitionImageLayout(VkImage image, VkImageLayout oldLayout, VkImageLayout newLayout) {
    VkCommandPoolCreateInfo poolInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .queueFamilyIndex = m_queueFamilyIndex,
    };
    VkCommandPool commandPool;
    vkCreateCommandPool(m_device, &poolInfo, nullptr, &commandPool);

    VkCommandBufferAllocateInfo allocInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO,
        .pNext = nullptr,
        .commandPool = commandPool,
        .level = VK_COMMAND_BUFFER_LEVEL_PRIMARY,
        .commandBufferCount = 1,
    };
    VkCommandBuffer commandBuffer;
    vkAllocateCommandBuffers(m_device, &allocInfo, &commandBuffer);

    VkCommandBufferBeginInfo beginInfo = {
        .sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO,
        .pNext = nullptr,
        .flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT,
        .pInheritanceInfo = nullptr,
    };
    vkBeginCommandBuffer(commandBuffer, &beginInfo);

    VkImageMemoryBarrier barrier = {
        .sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER,
        .pNext = nullptr,
        .srcAccessMask = 0,
        .dstAccessMask = 0,
        .oldLayout = oldLayout,
        .newLayout = newLayout,
        .srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED,
        .image = image,
        .subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1},
    };

    VkPipelineStageFlags sourceStage;
    VkPipelineStageFlags destinationStage;

    if (oldLayout == VK_IMAGE_LAYOUT_PREINITIALIZED && newLayout == VK_IMAGE_LAYOUT_GENERAL) {
        barrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceStage = VK_PIPELINE_STAGE_HOST_BIT;
        destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    } else {
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = 0;
        sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        destinationStage = VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT;
    }

    vkCmdPipelineBarrier(commandBuffer, sourceStage, destinationStage, 0, 0, nullptr, 0, nullptr, 1, &barrier);

    vkEndCommandBuffer(commandBuffer);

    VkSubmitInfo submitInfo = {
        .sType = VK_STRUCTURE_TYPE_SUBMIT_INFO,
        .pNext = nullptr,
        .waitSemaphoreCount = 0,
        .pWaitSemaphores = nullptr,
        .pWaitDstStageMask = nullptr,
        .commandBufferCount = 1,
        .pCommandBuffers = &commandBuffer,
        .signalSemaphoreCount = 0,
        .pSignalSemaphores = nullptr,
    };

    vkQueueSubmit(m_queue, 1, &submitInfo, VK_NULL_HANDLE);
    vkQueueWaitIdle(m_queue);

    vkFreeCommandBuffers(m_device, commandPool, 1, &commandBuffer);
    vkDestroyCommandPool(m_device, commandPool, nullptr);
}
