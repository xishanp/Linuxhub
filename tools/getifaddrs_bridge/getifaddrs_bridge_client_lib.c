/*
 * LinuxHub getifaddrs bridge client.
 *
 * The container cannot see Android's network namespace.  This library forwards
 * getifaddrs(3) to a small native helper running outside PRoot.
 *
 * Protocol: "TIF1" + uint32 payload length (network byte order) + payload.
 * Each interface is encoded with explicit field lengths so IPv6 sockaddr_in6
 * records are never truncated to sizeof(struct sockaddr).
 */
#define _GNU_SOURCE

#include <arpa/inet.h>
#include <dlfcn.h>
#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#define TIF_MAGIC "TIF1"
#define TIF_HEADER_SIZE 8u
#define TIF_MAX_PAYLOAD (256u * 1024u)
#define TIF_DEFAULT_SOCKET "/tmp/.getifaddrs-bridge"
#define TIF_CONNECT_TIMEOUT_SECONDS 2

typedef int (*real_getifaddrs_fn)(struct ifaddrs **);

static real_getifaddrs_fn real_getifaddrs;
static int real_getifaddrs_resolved;

static int tif_read_exact(int fd, void *buffer, size_t length) {
    unsigned char *cursor = (unsigned char *)buffer;
    size_t done = 0;
    while (done < length) {
        ssize_t count = read(fd, cursor + done, length - done);
        if (count == 0) return 0;
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        done += (size_t)count;
    }
    return 1;
}

static int tif_write_exact(int fd, const void *buffer, size_t length) {
    const unsigned char *cursor = (const unsigned char *)buffer;
    size_t done = 0;
    while (done < length) {
        ssize_t count = write(fd, cursor + done, length - done);
        if (count < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        if (count == 0) return -1;
        done += (size_t)count;
    }
    return 0;
}

static uint32_t tif_get_u32(const unsigned char *cursor) {
    uint32_t value;
    memcpy(&value, cursor, sizeof(value));
    return ntohl(value);
}

static int tif_field(const unsigned char **cursor, size_t *remaining,
                     const unsigned char **data, size_t *length) {
    if (*remaining < sizeof(uint32_t)) return -1;
    uint32_t encoded = tif_get_u32(*cursor);
    *cursor += sizeof(uint32_t);
    *remaining -= sizeof(uint32_t);
    if (encoded > *remaining) return -1;
    *data = *cursor;
    *length = encoded;
    *cursor += encoded;
    *remaining -= encoded;
    return 0;
}

static int tif_alloc_field(const unsigned char *data, size_t length,
                           void **destination) {
    if (length == 0) {
        *destination = NULL;
        return 0;
    }
    if (length > sizeof(struct sockaddr_storage)) return -1;
    void *copy = malloc(length);
    if (copy == NULL) return -1;
    memcpy(copy, data, length);
    *destination = copy;
    return 0;
}

static int tif_deserialize(const unsigned char *payload, size_t payload_size,
                           struct ifaddrs **result) {
    const unsigned char *cursor = payload;
    size_t remaining = payload_size;
    struct ifaddrs *head = NULL;
    struct ifaddrs **tail = &head;

    if (result == NULL) return -1;
    *result = NULL;

    while (remaining > 0) {
        const unsigned char *name_data;
        size_t name_length;
        const unsigned char *addr_data;
        size_t addr_length;
        const unsigned char *mask_data;
        size_t mask_length;
        const unsigned char *other_data;
        size_t other_length;
        const unsigned char *ifa_data;
        size_t ifa_data_length;

        if (tif_field(&cursor, &remaining, &name_data, &name_length) < 0 ||
            name_length > 255 ||
            tif_field(&cursor, &remaining, &addr_data, &addr_length) < 0 ||
            tif_field(&cursor, &remaining, &mask_data, &mask_length) < 0 ||
            tif_field(&cursor, &remaining, &other_data, &other_length) < 0 ||
            tif_field(&cursor, &remaining, &ifa_data, &ifa_data_length) < 0) {
            goto fail;
        }

        if (remaining < sizeof(uint32_t)) goto fail;
        unsigned int flags;
        memcpy(&flags, cursor, sizeof(flags));
        cursor += sizeof(flags);
        remaining -= sizeof(flags);
        flags = ntohl(flags);

        struct ifaddrs *ifa = calloc(1, sizeof(*ifa));
        if (ifa == NULL) goto fail;

        ifa->ifa_name = malloc(name_length + 1);
        if (ifa->ifa_name == NULL) {
            free(ifa);
            goto fail;
        }
        memcpy(ifa->ifa_name, name_data, name_length);
        ifa->ifa_name[name_length] = '\0';
        ifa->ifa_flags = flags;

        if (tif_alloc_field(addr_data, addr_length, (void **)&ifa->ifa_addr) < 0 ||
            tif_alloc_field(mask_data, mask_length, (void **)&ifa->ifa_netmask) < 0 ||
            tif_alloc_field(other_data, other_length,
                            (void **)((flags & IFF_POINTOPOINT) ?
                                      &ifa->ifa_dstaddr : &ifa->ifa_broadaddr)) < 0) {
            free(ifa->ifa_name);
            free(ifa->ifa_addr);
            free(ifa->ifa_netmask);
            free(ifa->ifa_broadaddr);
            free(ifa);
            goto fail;
        }
        /* Statistics are intentionally not transported. */
        ifa->ifa_data = NULL;
        (void)ifa_data;
        (void)ifa_data_length;

        *tail = ifa;
        tail = &ifa->ifa_next;
    }

    *result = head;
    return 0;

fail:
    if (head != NULL) freeifaddrs(head);
    return -1;
}

static real_getifaddrs_fn tif_resolve_real(void) {
    if (!real_getifaddrs_resolved) {
        real_getifaddrs_resolved = 1;
        real_getifaddrs = (real_getifaddrs_fn)dlsym(RTLD_NEXT, "getifaddrs");
    }
    return real_getifaddrs;
}

static int tif_fallback(struct ifaddrs **ifap) {
    real_getifaddrs_fn real = tif_resolve_real();
    if (real == NULL) {
        errno = ENOSYS;
        return -1;
    }
    return real(ifap);
}

__attribute__((visibility("default")))
int getifaddrs(struct ifaddrs **ifap) {
    if (ifap == NULL) {
        errno = EINVAL;
        return -1;
    }
    *ifap = NULL;

    const char *socket_path = getenv("DORO_GETIFADDRS_SOCKET");
    if (socket_path == NULL || socket_path[0] == '\0') socket_path = TIF_DEFAULT_SOCKET;

    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return tif_fallback(ifap);

    struct timeval timeout = { TIF_CONNECT_TIMEOUT_SECONDS, 0 };
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(socket_path) >= sizeof(address.sun_path)) {
        close(fd);
        return tif_fallback(ifap);
    }
    strcpy(address.sun_path, socket_path);

    if (connect(fd, (struct sockaddr *)&address, sizeof(address)) < 0) {
        close(fd);
        return tif_fallback(ifap);
    }

    unsigned char request[TIF_HEADER_SIZE];
    memcpy(request, TIF_MAGIC, 4);
    uint32_t zero = htonl(0);
    memcpy(request + 4, &zero, sizeof(zero));
    if (tif_write_exact(fd, request, sizeof(request)) < 0) {
        close(fd);
        return tif_fallback(ifap);
    }

    unsigned char header[TIF_HEADER_SIZE];
    if (tif_read_exact(fd, header, sizeof(header)) != 1 ||
        memcmp(header, TIF_MAGIC, 4) != 0) {
        close(fd);
        return tif_fallback(ifap);
    }
    uint32_t encoded_size;
    memcpy(&encoded_size, header + 4, sizeof(encoded_size));
    uint32_t payload_size = ntohl(encoded_size);
    if (payload_size > TIF_MAX_PAYLOAD) {
        close(fd);
        return tif_fallback(ifap);
    }

    unsigned char *payload = NULL;
    if (payload_size != 0) {
        payload = malloc(payload_size);
        if (payload == NULL) {
            close(fd);
            return tif_fallback(ifap);
        }
        if (tif_read_exact(fd, payload, payload_size) != 1) {
            free(payload);
            close(fd);
            return tif_fallback(ifap);
        }
    }
    close(fd);

    if (tif_deserialize(payload, payload_size, ifap) < 0) {
        free(payload);
        *ifap = NULL;
        return tif_fallback(ifap);
    }
    free(payload);
    return 0;
}
