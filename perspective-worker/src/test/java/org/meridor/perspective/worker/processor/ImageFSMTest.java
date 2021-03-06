package org.meridor.perspective.worker.processor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.meridor.perspective.backend.EntityGenerator;
import org.meridor.perspective.backend.storage.ImagesAware;
import org.meridor.perspective.backend.storage.Storage;
import org.meridor.perspective.beans.DestinationName;
import org.meridor.perspective.beans.Image;
import org.meridor.perspective.beans.ImageState;
import org.meridor.perspective.events.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import ru.yandex.qatools.fsm.Yatomata;
import ru.yandex.qatools.fsm.impl.FSMBuilder;

import java.util.concurrent.BlockingQueue;

import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertThat;
import static org.meridor.perspective.beans.ImageState.*;
import static org.meridor.perspective.events.EventFactory.imageToEvent;
import static org.springframework.test.annotation.DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD;

@ContextConfiguration(locations = "/META-INF/spring/fsm-test-context.xml")
@RunWith(SpringJUnit4ClassRunner.class)
@DirtiesContext(classMode = BEFORE_EACH_TEST_METHOD)
public class ImageFSMTest {

    @Autowired
    private ApplicationContext applicationContext;
    
    @Autowired
    private ImagesAware imagesAware;

    @Autowired
    private Storage storage;

    @Test
    public void testOnImageQueued() {
        testOnImageSyncEvent(ImageQueuedEvent.class, QUEUED);
    }
    
    @Test
    public void testOnImageSaving() {
        testOnImageSyncEvent(ImageSavingEvent.class, SAVING);
    }
    
    @Test
    public void onImageAdding() {
        testOnImageEvent(ImageSavingEvent.class, SAVING, false);
    }
    
    @Test
    public void testOnImageSaved() {
        testOnImageSyncEvent(ImageSavedEvent.class, SAVED);
    }
    
    @Test
    public void testOnImageDeletingSync() {
        testOnImageSyncEvent(ImageDeletingEvent.class, DELETING);
    }

    @Test
    public void testOnImageDeleting() {
        Image image = EntityGenerator.getImage();
        imagesAware.saveImage(image);
        String imageId = image.getId();
        assertThat(imagesAware.imageExists(imageId), is(true));
        fireEvent(ImageDeletingEvent.class, image, false);
        assertThat(imagesAware.imageExists(imageId), is(false));
    }

    @Test
    public void testOnImageError() {
        testOnImageSyncEvent(ImageErrorEvent.class, ERROR);
    }
    
    private void testOnImageSyncEvent(Class<? extends ImageEvent> cls, ImageState correctState) {
        testOnImageEvent(cls, correctState, true);
    }
    
    private void testOnImageEvent(Class<? extends ImageEvent> cls, ImageState correctState, boolean isSyncEvent) {
        Image image = EntityGenerator.getImage();
        String imageId = image.getId();
        assertThat(imagesAware.imageExists(imageId), is(false));
        fireEvent(cls, image, isSyncEvent);
        assertThat(imagesAware.imageExists(imageId), is(true));
        assertThat(imagesAware.getImage(imageId).get().getState(), equalTo(correctState));
    }

    private void fireEvent(Class<? extends ImageEvent> cls, Image image, boolean isSyncEvent) {
        fireEvent(null, cls, image, isSyncEvent);
    }

    private void fireEvent(ImageEvent initialState, Class<? extends ImageEvent> cls, Image image, boolean isSyncEvent) {
        Yatomata<ImageFSM> fsm = initialState != null ?
                getFSMBuilder().build(initialState) :
                getFSMBuilder().build();
        ImageEvent imageEvent = EventFactory.imageEvent(cls, image);
        imageEvent.setSync(isSyncEvent);
        fsm.fire(imageEvent);
    }
    
    private Yatomata.Builder<ImageFSM> getFSMBuilder() {
        return new FSMBuilder<>(applicationContext.getBean(ImageFSM.class));
    }

    @Test
    public void testSendMailOnImageError() {
        BlockingQueue<Object> queue = storage.getQueue(DestinationName.MAIL.value());
        assertThat(queue, is(empty()));
        Image image = EntityGenerator.getImage();
        image.setState(ImageState.SAVING);
        imagesAware.saveImage(image);
        ImageEvent initialState = imageToEvent(image);
        fireEvent(initialState, ImageErrorEvent.class, image, true);
        assertThat(queue, hasSize(1));
        fireEvent(ImageErrorEvent.class, image, true);
        assertThat(queue, hasSize(1)); //Does not change
    }
    
}