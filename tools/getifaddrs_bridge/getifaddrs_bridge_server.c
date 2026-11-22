/*
 * LinuxHub getifaddrs bridge server.
 *
 * Runs as the Android app UID outside PRoot and exposes Android's real
 * getifaddrs(3) result over a private Unix-domain socket.
 */
#define _GNU_SOURCE

#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <ifaddrs.h>
#include <linux/if_packet.h>
#include <net/if.h>
#include <signal.h>
#include <limits.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/time.h>
#include <sys/un.h>
#include <unistd.h>

#ifdef __linux__
#include <sys/prctl.h>
#endif

#define TIF_MAGIC "TIF1"
#define TIF_HEADER_SIZE 8u
#define TIF_MAX_PAYLOAD (256u * 1024u)
#define TIF_TIMEOUT_SECONDS 2

struct tif_buffer {
    unsigned char *data;
    size_t size;
    size_t capacity;
};

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

static int tif_buffer_reserve(struct tif_buffer *buffer, size_t extra) {
    if (extra > TIF_MAX_PAYLOAD || buffer->size > TIF_MAX_PAYLOAD - extra) {
        return -1;
    }
    size_t needed = buffer->size + extra;
    if (needed <= buffer->capacity) return 0;
    size_t capacity = buffer->capacity ? buffer->capacity : 1024;
    while (capacity < needed) capacity *= 2;
    if (capacity > TIF_MAX_PAYLOAD) capacity = TIF_MAX_PAYLOAD;
    unsigned char *grown = realloc(buffer->data, capacity);
    if (grown == NULL) return -1;
    buffer->data = grown;
    buffer->capacity = capacity;
    return 0;
}

static int tif_append_bytes(struct tif_buffer *buffer, const void *data, size_t length) {
    if (tif_buffer_reserve(buffer, length) < 0) return -1;
    memcpy(buffer->data + buffer->size, data, length);
    buffer->size += length;
    return 0;
}

static int tif_append_u32(struct tif_buffer *buffer, uint32_t value) {
    uint32_t encoded = htonl(value);
    return tif_append_bytes(buffer, &encoded, sizeof(encoded));
}

static int tif_append_field(struct tif_buffer *buffer, const struct sockaddr *address) {
    if (address == NULL) return tif_append_u32(buffer, 0);

    size_t length;
    switch (address->sa_family) {
        case AF_INET:
            length = sizeof(struct sockaddr_in);
            break;
        case AF_INET6:
            length = sizeof(struct sockaddr_in6);
            break;
#ifdef AF_PACKET
        case AF_PACKET:
            length = sizeof(struct sockaddr_ll);
            break;
#endif
        default:
            length = sizeof(struct sockaddr);
            break;
    }
    if (length > UINT32_MAX) return -1;
    if (tif_append_u32(buffer, (uint32_t)length) < 0) return -1;
    return tif_append_bytes(buffer, address, length);
}

static int tif_serialize(struct ifaddrs *list, struct tif_buffer *buffer) {
    for (struct ifaddrs *ifa = list; ifa != NULL; ifa = ifa->ifa_next) {
        const char *name = ifa->ifa_name ? ifa->ifa_name : "";
        size_t name_length = strnlen(name, IFNAMSIZ);
        if (name_length > UINT32_MAX) return -1;

        if (tif_append_u32(buffer, (uint32_t)name_length) < 0 ||
            tif_append_bytes(buffer, name, name_length) < 0 ||
            tif_append_field(buffer, ifa->ifa_addr) < 0 ||
            tif_append_field(buffer, ifa->ifa_netmask) < 0) {
            return -1;
        }

        const struct sockaddr *other = NULL;
        if (ifa->ifa_flags & IFF_BROADCAST) other = ifa->ifa_broadaddr;
        else if (ifa->ifa_flags & IFF_POINTOPOINT) other = ifa->ifa_dstaddr;
        if (tif_append_field(buffer, other) < 0 || tif_append_u32(buffer, 0) < 0) {
            return -1;
        }

        /* Keep the field order identical to the client decoder. */
        if (tif_append_u32(buffer, (uint32_t)ifa->ifa_flags) < 0) return -1;
    }
    return 0;
}

static int tif_send_payload(int fd, struct tif_buffer *payload) {
    unsigned char header[TIF_HEADER_SIZE];
    memcpy(header, TIF_MAGIC, 4);
    uint32_t encoded_size = htonl((uint32_t)payload->size);
    memcpy(header + 4, &encoded_size, sizeof(encoded_size));
    if (tif_write_exact(fd, header, sizeof(header)) < 0) return -1;
    if (payload->size != 0 && tif_write_exact(fd, payload->data, payload->size) < 0) {
        return -1;
    }
    return 0;
}

static void tif_handle_connection(int fd) {
    struct timeval timeout = { TIF_TIMEOUT_SECONDS, 0 };
    (void)setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &timeout, sizeof(timeout));
    (void)setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &timeout, sizeof(timeout));

    unsigned char header[TIF_HEADER_SIZE];
    int status = tif_read_exact(fd, header, sizeof(header));
    if (status == 0) return;
    if (status < 0 || memcmp(header, TIF_MAGIC, 4) != 0) return;
    uint32_t encoded_size;
    memcpy(&encoded_size, header + 4, sizeof(encoded_size));
    if (ntohl(encoded_size) != 0) return;

    struct ifaddrs *list = NULL;
    if (getifaddrs(&list) < 0) return;

    struct tif_buffer payload = { 0 };
    int result = tif_serialize(list, &payload);
    if (result == 0) (void)tif_send_payload(fd, &payload);
    free(payload.data);
    freeifaddrs(list);
}

static int tif_acquire_lock(const char *socket_path) {
    char lock_path[PATH_MAX];
    const char *configured = getenv("DORO_GETIFADDRS_LOCK");
    if (configured != NULL && configured[0] != '\0') {
        if (strlen(configured) >= sizeof(lock_path)) return -1;
        strcpy(lock_path, configured);
    } else {
        int length = snprintf(lock_path, sizeof(lock_path), "%s.lock", socket_path);
        if (length < 0 || (size_t)length >= sizeof(lock_path)) return -1;
    }
    int fd = open(lock_path, O_CREAT | O_RDWR, 0600);
    if (fd < 0) return -1;
    if (flock(fd, LOCK_EX | LOCK_NB) != 0) {
        close(fd);
        return -1;
    }
    return fd;
}

int main(int argc, char **argv) {
    if (argc != 2) {
        fprintf(stderr, "usage: %s <socket_path>\n", argv[0]);
        return 2;
    }

    signal(SIGPIPE, SIG_IGN);
#ifdef __linux__
    (void)prctl(PR_SET_PDEATHSIG, SIGTERM);
#endif

    int lock_fd = tif_acquire_lock(argv[1]);
    if (lock_fd < 0) return 1;

    int listener = socket(AF_UNIX, SOCK_STREAM, 0);
    if (listener < 0) {
        close(lock_fd);
        return 1;
    }

    struct sockaddr_un address;
    memset(&address, 0, sizeof(address));
    address.sun_family = AF_UNIX;
    if (strlen(argv[1]) >= sizeof(address.sun_path)) {
        close(listener);
        close(lock_fd);
        return 2;
    }
    strcpy(address.sun_path, argv[1]);

    struct stat existing;
    if (lstat(address.sun_path, &existing) == 0 && !S_ISSOCK(existing.st_mode)) {
        fprintf(stderr, "socket path is not a socket: %s\n", address.sun_path);
        close(listener);
        close(lock_fd);
        return 1;
    }
    (void)unlink(address.sun_path);
    if (bind(listener, (struct sockaddr *)&address, sizeof(address)) < 0 ||
        chmod(address.sun_path, 0600) < 0 ||
        listen(listener, 8) < 0) {
        close(listener);
        close(lock_fd);
        return 1;
    }

    for (;;) {
        int connection = accept(listener, NULL, NULL);
        if (connection < 0) {
            if (errno == EINTR) continue;
            continue;
        }
        tif_handle_connection(connection);
        close(connection);
    }
}
